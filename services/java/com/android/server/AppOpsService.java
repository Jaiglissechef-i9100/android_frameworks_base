/*
 * Copyright (c) 2013, The Linux Foundation. All rights reserved.
 * Not a Contribution.
 *
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import android.app.AppOpsManager;
import android.app.Dialog;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.AtomicFile;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.util.TimeUtils;
import android.util.Xml;

import com.android.internal.app.IAppOpsService;
import com.android.internal.app.IAppOpsCallback;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.XmlUtils;
import com.android.server.PermissionDialogResult.Result;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

public class AppOpsService extends IAppOpsService.Stub {
    static final String TAG = "AppOps";
    static final boolean DEBUG = false;
    static final int SHOW_PERMISSION_DIALOG = 1;
    static final String WHITELIST_FILE = "persist.sys.whitelist";

    // Write at most every 30 minutes.
    static final long WRITE_DELAY = DEBUG ? 1000 : 30*60*1000;

    Context mContext;
    final AtomicFile mFile;
    final Handler mHandler;
    final boolean mStrictEnable;

    Looper mLooper;

    boolean mWriteScheduled;
    final Runnable mWriteRunner = new Runnable() {
        public void run() {
            synchronized (AppOpsService.this) {
                mWriteScheduled = false;
                AsyncTask<Void, Void, Void> task = new AsyncTask<Void, Void, Void>() {
                    @Override protected Void doInBackground(Void... params) {
                        writeState();
                        return null;
                    }
                };
                task.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, (Void[])null);
            }
        }
    };

    final SparseArray<HashMap<String, Ops>> mUidOps
            = new SparseArray<HashMap<String, Ops>>();

    public final static class Ops extends SparseArray<Op> {
        public final String packageName;
        public final int uid;

        public Ops(String _packageName, int _uid) {
            packageName = _packageName;
            uid = _uid;
        }
    }

    public final static class Op {
        public final int uid;
        public final String packageName;
        public final int op;
        public int mode;
        public int duration;
        public long time;
        public long rejectTime;
        public int nesting;
        public int allowedCount;
        public int ignoredCount;
        public int noteOpCount;
        public int startOpCount;
        public PermissionDialogResult dialogResult;
        final ArrayList<IBinder> mClientTokens;

        public Op(int _uid, String _packageName, int _op, int _mode) {
            uid = _uid;
            packageName = _packageName;
            op = _op;
            mode = _mode;
            dialogResult = new PermissionDialogResult();
            mClientTokens = new ArrayList<IBinder>();
        }
    }

    final SparseArray<ArrayList<Callback>> mOpModeWatchers
            = new SparseArray<ArrayList<Callback>>();
    final ArrayMap<String, ArrayList<Callback>> mPackageModeWatchers
            = new ArrayMap<String, ArrayList<Callback>>();
    final ArrayMap<IBinder, Callback> mModeWatchers
            = new ArrayMap<IBinder, Callback>();

    public final class Callback implements DeathRecipient {
        final IAppOpsCallback mCallback;

        public Callback(IAppOpsCallback callback) {
            mCallback = callback;
            try {
                mCallback.asBinder().linkToDeath(this, 0);
            } catch (RemoteException e) {
            }
        }

        public void unlinkToDeath() {
            mCallback.asBinder().unlinkToDeath(this, 0);
        }

        @Override
        public void binderDied() {
            stopWatchingMode(mCallback);
        }
    }

    final ArrayMap<IBinder, ClientState> mClients = new ArrayMap<IBinder, ClientState>();

    public final class ClientState extends Binder implements DeathRecipient {
        final IBinder mAppToken;
        final int mPid;
        final ArrayList<Op> mStartedOps;

        public ClientState(IBinder appToken) {
            mAppToken = appToken;
            mPid = Binder.getCallingPid();
            if (appToken instanceof Binder) {
                // For local clients, there is no reason to track them.
                mStartedOps = null;
            } else {
                mStartedOps = new ArrayList<Op>();
                try {
                    mAppToken.linkToDeath(this, 0);
                } catch (RemoteException e) {
                }
            }
        }

        @Override
        public String toString() {
            return "ClientState{" +
                    "mAppToken=" + mAppToken +
                    ", " + (mStartedOps != null ? ("pid=" + mPid) : "local") +
                    '}';
        }

        @Override
        public void binderDied() {
            synchronized (AppOpsService.this) {
                for (int i=mStartedOps.size()-1; i>=0; i--) {
                    finishOperationLocked(mStartedOps.get(i));
                }
                mClients.remove(mAppToken);
            }
        }
    }

    public AppOpsService(File storagePath) {
        mStrictEnable = AppOpsManager.isStrictEnable();
        mFile = new AtomicFile(storagePath);
        mLooper = Looper.myLooper();
        mHandler = new Handler() {
            public void handleMessage(Message msg) {
                switch (msg.what) {
                case SHOW_PERMISSION_DIALOG: {
                    HashMap<String, Object> data =
                        (HashMap<String, Object>) msg.obj;
                    synchronized (this) {
                        Op op = (Op) data.get("op");
                        Result res = (Result) data.get("result");
                        op.dialogResult.register(res);
                        if(op.dialogResult.mDialog == null) {
                            Integer code = (Integer) data.get("code");
                            Integer uid  = (Integer) data.get("uid");
                            String packageName =
                                (String) data.get("packageName");
                            Dialog d = new PermissionDialog(mContext,
                                AppOpsService.this, code, uid,
                                packageName);
                            op.dialogResult.mDialog = (PermissionDialog)d;
                            d.show();
                        }
                    }
                }break;
                }
            }
        };
        readWhitelist();
        readState();
    }

    public void publish(Context context) {
        mContext = context;
        ServiceManager.addService(Context.APP_OPS_SERVICE, asBinder());
    }

    public void systemReady() {
        synchronized (this) {
            boolean changed = false;
            for (int i=0; i<mUidOps.size(); i++) {
                HashMap<String, Ops> pkgs = mUidOps.valueAt(i);
                Iterator<Ops> it = pkgs.values().iterator();
                while (it.hasNext()) {
                    Ops ops = it.next();
                    int curUid = -1;
                    try {
                        curUid = mContext.getPackageManager().getPackageUid(ops.packageName,
                                UserHandle.getUserId(ops.uid));
                    } catch (NameNotFoundException e) {
                        if ("android".equals(ops.packageName)) {
                            curUid = Process.SYSTEM_UID;
                        }
                    }
                    if (curUid != ops.uid) {
                        // Do not prune apps that are not currently present in the device
                        // (like sdcards ones). During booting sdcards are not available but
                        // must not be purge from appops, because they are still present
                        // in the android app database.
                        String pkgName = mContext.getPackageManager().getNameForUid(ops.uid);
                        if (curUid != -1 || pkgName == null || !pkgName.equals(ops.packageName)) {
                            Slog.i(TAG, "Pruning old package " + ops.packageName
                                    + "/" + ops.uid + ": new uid=" + curUid);
                            it.remove();
                            changed = true;
                        }
                    }
                }
                if (pkgs.size() <= 0) {
                    mUidOps.removeAt(i);
                }
            }
            if (changed) {
                scheduleWriteLocked();
            }
        }
    }

    public void packageRemoved(int uid, String packageName) {
        synchronized (this) {
            HashMap<String, Ops> pkgs = mUidOps.get(uid);
            if (pkgs != null) {
                if (pkgs.remove(packageName) != null) {
                    if (pkgs.size() <= 0) {
                        mUidOps.remove(uid);
                    }
                    scheduleWriteLocked();
                }
            }
        }
    }

    public void uidRemoved(int uid) {
        synchronized (this) {
            if (mUidOps.indexOfKey(uid) >= 0) {
                mUidOps.remove(uid);
                scheduleWriteLocked();
            }
        }
    }

    public void shutdown() {
        Slog.w(TAG, "Writing app ops before shutdown...");
        boolean doWrite = false;
        synchronized (this) {
            if (mWriteScheduled) {
                mWriteScheduled = false;
                doWrite = true;
            }
        }
        if (doWrite) {
            writeState();
        }
    }

    private ArrayList<AppOpsManager.OpEntry> collectOps(Ops pkgOps, int[] ops) {
        ArrayList<AppOpsManager.OpEntry> resOps = null;
        if (ops == null) {
            resOps = new ArrayList<AppOpsManager.OpEntry>();
            for (int j=0; j<pkgOps.size(); j++) {
                Op curOp = pkgOps.valueAt(j);
                resOps.add(new AppOpsManager.OpEntry(curOp.op, curOp.mode, curOp.time,
                        curOp.rejectTime, curOp.duration,
                        curOp.allowedCount, curOp.ignoredCount));
            }
        } else {
            for (int j=0; j<ops.length; j++) {
                Op curOp = pkgOps.get(ops[j]);
                if (curOp != null) {
                    if (resOps == null) {
                        resOps = new ArrayList<AppOpsManager.OpEntry>();
                    }
                    resOps.add(new AppOpsManager.OpEntry(curOp.op, curOp.mode, curOp.time,
                            curOp.rejectTime, curOp.duration,
                            curOp.allowedCount, curOp.ignoredCount));
                }
            }
        }
        return resOps;
    }

    @Override
    public List<AppOpsManager.PackageOps> getPackagesForOps(int[] ops) {
        mContext.enforcePermission(android.Manifest.permission.GET_APP_OPS_STATS,
                Binder.getCallingPid(), Binder.getCallingUid(), null);
        ArrayList<AppOpsManager.PackageOps> res = null;
        synchronized (this) {
            for (int i=0; i<mUidOps.size(); i++) {
                HashMap<String, Ops> packages = mUidOps.valueAt(i);
                for (Ops pkgOps : packages.values()) {
                    ArrayList<AppOpsManager.OpEntry> resOps = collectOps(pkgOps, ops);
                    if (resOps != null) {
                        if (res == null) {
                            res = new ArrayList<AppOpsManager.PackageOps>();
                        }
                        AppOpsManager.PackageOps resPackage = new AppOpsManager.PackageOps(
                                pkgOps.packageName, pkgOps.uid, resOps);
                        res.add(resPackage);
                    }
                }
            }
        }
        return res;
    }

    @Override
    public List<AppOpsManager.PackageOps> getOpsForPackage(int uid, String packageName,
            int[] ops) {
        mContext.enforcePermission(android.Manifest.permission.GET_APP_OPS_STATS,
                Binder.getCallingPid(), Binder.getCallingUid(), null);
        synchronized (this) {
            Ops pkgOps = getOpsLocked(uid, packageName, false);
            if (pkgOps == null) {
                return null;
            }
            ArrayList<AppOpsManager.OpEntry> resOps = collectOps(pkgOps, ops);
            if (resOps == null) {
                return null;
            }
            ArrayList<AppOpsManager.PackageOps> res = new ArrayList<AppOpsManager.PackageOps>();
            AppOpsManager.PackageOps resPackage = new AppOpsManager.PackageOps(
                    pkgOps.packageName, pkgOps.uid, resOps);
            res.add(resPackage);
            return res;
        }
    }

    private void pruneOp(Op op, int uid, String packageName) {
        if (op.time == 0 && op.rejectTime == 0) {
            Ops ops = getOpsLocked(uid, packageName, false);
            if (ops != null) {
                ops.remove(op.op);
                if (ops.size() <= 0) {
                    HashMap<String, Ops> pkgOps = mUidOps.get(uid);
                    if (pkgOps != null) {
                        pkgOps.remove(ops.packageName);
                        if (pkgOps.size() <= 0) {
                            mUidOps.remove(uid);
                        }
                    }
                }
            }
        }
    }

    @Override
    public void setMode(int code, int uid, String packageName, int mode) {
        verifyIncomingUid(uid);
        verifyIncomingOp(code);
        ArrayList<Callback> repCbs = null;
        code = AppOpsManager.opToSwitch(code);
        synchronized (this) {
            Op op = getOpLocked(code, uid, packageName, true);
            if (op != null) {
                if (op.mode != mode) {
                    op.mode = mode;
                    ArrayList<Callback> cbs = mOpModeWatchers.get(code);
                    if (cbs != null) {
                        if (repCbs == null) {
                            repCbs = new ArrayList<Callback>();
                        }
                        repCbs.addAll(cbs);
                    }
                    cbs = mPackageModeWatchers.get(packageName);
                    if (cbs != null) {
                        if (repCbs == null) {
                            repCbs = new ArrayList<Callback>();
                        }
                        repCbs.addAll(cbs);
                    }
                    if (mode == getDefaultMode(op.op, op.uid, op.packageName)) {
                        // If going into the default mode, prune this op
                        // if there is nothing else interesting in it.
                        pruneOp(op, uid, packageName);
                    }
                    scheduleWriteNowLocked();
                }
            }
        }
        if (repCbs != null) {
            for (int i=0; i<repCbs.size(); i++) {
                try {
                    repCbs.get(i).mCallback.opChanged(code, packageName);
                } catch (RemoteException e) {
                }
            }
        }
    }

    private static HashMap<Callback, ArrayList<Pair<String, Integer>>> addCallbacks(
            HashMap<Callback, ArrayList<Pair<String, Integer>>> callbacks,
            String packageName, int op, ArrayList<Callback> cbs) {
        if (cbs == null) {
            return callbacks;
        }
        if (callbacks == null) {
            callbacks = new HashMap<Callback, ArrayList<Pair<String, Integer>>>();
        }
        for (int i=0; i<cbs.size(); i++) {
            Callback cb = cbs.get(i);
            ArrayList<Pair<String, Integer>> reports = callbacks.get(cb);
            if (reports == null) {
                reports = new ArrayList<Pair<String, Integer>>();
                callbacks.put(cb, reports);
            }
            reports.add(new Pair<String, Integer>(packageName, op));
        }
        return callbacks;
    }

    @Override
    public void resetAllModes() {
        mContext.enforcePermission(android.Manifest.permission.UPDATE_APP_OPS_STATS,
                Binder.getCallingPid(), Binder.getCallingUid(), null);
        HashMap<Callback, ArrayList<Pair<String, Integer>>> callbacks = null;
        synchronized (this) {
            boolean changed = false;
            for (int i=mUidOps.size()-1; i>=0; i--) {
                HashMap<String, Ops> packages = mUidOps.valueAt(i);
                Iterator<Map.Entry<String, Ops>> it = packages.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<String, Ops> ent = it.next();
                    String packageName = ent.getKey();
                    Ops pkgOps = ent.getValue();
                    for (int j=pkgOps.size()-1; j>=0; j--) {
                        Op curOp = pkgOps.valueAt(j);
                        if (AppOpsManager.opAllowsReset(curOp.op)
                                && curOp.mode != getDefaultMode(curOp.op, curOp.uid, curOp.packageName)) {
                            curOp.mode = getDefaultMode(curOp.op, curOp.uid, curOp.packageName);
                            changed = true;
                            callbacks = addCallbacks(callbacks, packageName, curOp.op,
                                    mOpModeWatchers.get(curOp.op));
                            callbacks = addCallbacks(callbacks, packageName, curOp.op,
                                    mPackageModeWatchers.get(packageName));
                            if (curOp.time == 0 && curOp.rejectTime == 0) {
                                pkgOps.removeAt(j);
                            }
                        }
                    }
                    if (pkgOps.size() == 0) {
                        it.remove();
                    }
                }
                if (packages.size() == 0) {
                    mUidOps.removeAt(i);
                }
            }
            if (changed) {
                scheduleWriteNowLocked();
            }
        }
        if (callbacks != null) {
            for (Map.Entry<Callback, ArrayList<Pair<String, Integer>>> ent : callbacks.entrySet()) {
                Callback cb = ent.getKey();
                ArrayList<Pair<String, Integer>> reports = ent.getValue();
                for (int i=0; i<reports.size(); i++) {
                    Pair<String, Integer> rep = reports.get(i);
                    try {
                        cb.mCallback.opChanged(rep.second, rep.first);
                    } catch (RemoteException e) {
                    }
                }
            }
        }
    }

    @Override
    public void startWatchingMode(int op, String packageName, IAppOpsCallback callback) {
        synchronized (this) {
            op = AppOpsManager.opToSwitch(op);
            Callback cb = mModeWatchers.get(callback.asBinder());
            if (cb == null) {
                cb = new Callback(callback);
                mModeWatchers.put(callback.asBinder(), cb);
            }
            if (op != AppOpsManager.OP_NONE) {
                ArrayList<Callback> cbs = mOpModeWatchers.get(op);
                if (cbs == null) {
                    cbs = new ArrayList<Callback>();
                    mOpModeWatchers.put(op, cbs);
                }
                cbs.add(cb);
            }
            if (packageName != null) {
                ArrayList<Callback> cbs = mPackageModeWatchers.get(packageName);
                if (cbs == null) {
                    cbs = new ArrayList<Callback>();
                    mPackageModeWatchers.put(packageName, cbs);
                }
                cbs.add(cb);
            }
        }
    }

    @Override
    public void stopWatchingMode(IAppOpsCallback callback) {
        synchronized (this) {
            Callback cb = mModeWatchers.remove(callback.asBinder());
            if (cb != null) {
                cb.unlinkToDeath();
                for (int i=mOpModeWatchers.size()-1; i>=0; i--) {
                    ArrayList<Callback> cbs = mOpModeWatchers.valueAt(i);
                    cbs.remove(cb);
                    if (cbs.size() <= 0) {
                        mOpModeWatchers.removeAt(i);
                    }
                }
                for (int i=mPackageModeWatchers.size()-1; i>=0; i--) {
                    ArrayList<Callback> cbs = mPackageModeWatchers.valueAt(i);
                    cbs.remove(cb);
                    if (cbs.size() <= 0) {
                        mPackageModeWatchers.removeAt(i);
                    }
                }
            }
        }
    }

    @Override
    public IBinder getToken(IBinder clientToken) {
        synchronized (this) {
            ClientState cs = mClients.get(clientToken);
            if (cs == null) {
                cs = new ClientState(clientToken);
                mClients.put(clientToken, cs);
            }
            return cs;
        }
    }

    @Override
    public int checkOperation(int code, int uid, String packageName) {
        verifyIncomingUid(uid);
        verifyIncomingOp(code);
        synchronized (this) {
            Op op = getOpLocked(AppOpsManager.opToSwitch(code), uid, packageName, false);
            if (op == null) {
                return getDefaultMode(code, uid, packageName);
            }
            return op.mode;
        }
    }

    @Override
    public int checkPackage(int uid, String packageName) {
        synchronized (this) {
            if (getOpsLocked(uid, packageName, true) != null) {
                return AppOpsManager.MODE_ALLOWED;
            } else {
                return AppOpsManager.MODE_ERRORED;
            }
        }
    }

    @Override
    public int noteOperation(int code, int uid, String packageName) {
        final Result userDialogResult;
        verifyIncomingUid(uid);
        verifyIncomingOp(code);
        synchronized (this) {
            Ops ops = getOpsLocked(uid, packageName, true);
            if (ops == null) {
                if (DEBUG) Log.d(TAG, "noteOperation: no op for code " + code + " uid " + uid
                        + " package " + packageName);
                return AppOpsManager.MODE_ERRORED;
            }
            Op op = getOpLocked(ops, code, true);
            if (op.duration == -1) {
                Slog.w(TAG, "Noting op not finished: uid " + uid + " pkg " + packageName
                        + " code " + code + " time=" + op.time + " duration=" + op.duration);
            }
            op.duration = 0;
            final int switchCode = AppOpsManager.opToSwitch(code);
            final Op switchOp = switchCode != code ? getOpLocked(ops, switchCode, true) : op;
            if (switchOp.mode == AppOpsManager.MODE_IGNORED ||
                switchOp.mode == AppOpsManager.MODE_ERRORED) {
                if (DEBUG) Log.d(TAG, "noteOperation: reject #" + op.mode + " for code "
                        + switchCode + " (" + code + ") uid " + uid + " package " + packageName);
                op.rejectTime = System.currentTimeMillis();
                op.ignoredCount++;
                return switchOp.mode;
            } else if(switchOp.mode == AppOpsManager.MODE_ALLOWED) {
                if (DEBUG) Log.d(TAG, "noteOperation: allowing code " + code + " uid " + uid
                        + " package " + packageName);
                op.time = System.currentTimeMillis();
                op.rejectTime = 0;
                op.allowedCount++;
                return AppOpsManager.MODE_ALLOWED;
            } else {
                if (Looper.myLooper() == mLooper) {
                    Log.e(TAG, "noteOperation: This method will deadlock if called from the main thread. (Code: "
                            + code + " uid: " + uid + " package: " + packageName + ")");
                    return switchOp.mode;
                }
                op.noteOpCount++;
                userDialogResult = askOperationLocked(code, uid, packageName,
                    switchOp);
            }
        }
        return userDialogResult.get();
    }

    @Override
    public int startOperation(IBinder token, int code, int uid, String packageName) {
        final Result userDialogResult;
        verifyIncomingUid(uid);
        verifyIncomingOp(code);
        ClientState client = (ClientState)token;
        synchronized (this) {
            Ops ops = getOpsLocked(uid, packageName, true);
            if (ops == null) {
                if (DEBUG) Log.d(TAG, "startOperation: no op for code " + code + " uid " + uid
                        + " package " + packageName);
                return AppOpsManager.MODE_ERRORED;
            }
            Op op = getOpLocked(ops, code, true);
            final int switchCode = AppOpsManager.opToSwitch(code);
            final Op switchOp = switchCode != code ? getOpLocked(ops, switchCode, true) : op;
            if (switchOp.mode == AppOpsManager.MODE_IGNORED ||
                switchOp.mode == AppOpsManager.MODE_ERRORED) {
                if (DEBUG) Log.d(TAG, "startOperation: reject #" + op.mode + " for code "
                        + switchCode + " (" + code + ") uid " + uid + " package " + packageName);
                op.rejectTime = System.currentTimeMillis();
                op.ignoredCount++;
                return switchOp.mode;
            } else if(switchOp.mode == AppOpsManager.MODE_ALLOWED) {
                if (DEBUG) Log.d(TAG, "startOperation: allowing code " + code + " uid "
                    + uid + " package " + packageName);
                if (op.nesting == 0) {
                    op.time = System.currentTimeMillis();
                    op.allowedCount++;
                    op.rejectTime = 0;
                    op.duration = -1;
                }
                op.nesting++;
                if (client.mStartedOps != null) {
                    client.mStartedOps.add(op);
                }
                return AppOpsManager.MODE_ALLOWED;
            } else {
                if (Looper.myLooper() == mLooper) {
                    Log.e(TAG, "startOperation: This method will deadlock if called from the main thread. (Code: "
                            + code + " uid: " + uid + " package: " + packageName +")");
                    return switchOp.mode;
                }
                op.startOpCount++;
                IBinder clientToken = client.mAppToken;
                op.mClientTokens.add(clientToken);
                userDialogResult = askOperationLocked(code, uid, packageName,
                    switchOp);
            }
        }
        return userDialogResult.get();
    }

    @Override
    public void finishOperation(IBinder token, int code, int uid, String packageName) {
        verifyIncomingUid(uid);
        verifyIncomingOp(code);
        ClientState client = (ClientState)token;
        synchronized (this) {
            Op op = getOpLocked(code, uid, packageName, true);
            if (op == null) {
                return;
            }
            if (client.mStartedOps != null) {
                if (!client.mStartedOps.remove(op)) {
                    throw new IllegalStateException("Operation not started: uid" + op.uid
                            + " pkg=" + op.packageName + " op=" + op.op);
                }
            }
            finishOperationLocked(op);
        }
    }

    void finishOperationLocked(Op op) {
        if (op.nesting <= 1) {
            if (op.nesting == 1) {
                op.duration = (int)(System.currentTimeMillis() - op.time);
                op.time += op.duration;
            } else {
                Slog.w(TAG, "Finishing op nesting under-run: uid " + op.uid + " pkg "
                        + op.packageName + " code " + op.op + " time=" + op.time
                        + " duration=" + op.duration + " nesting=" + op.nesting);
            }
            op.nesting = 0;
        } else {
            op.nesting--;
        }
    }

    private void verifyIncomingUid(int uid) {
        if (uid == Binder.getCallingUid()) {
            return;
        }
        if (Binder.getCallingPid() == Process.myPid()) {
            return;
        }
        mContext.enforcePermission(android.Manifest.permission.UPDATE_APP_OPS_STATS,
                Binder.getCallingPid(), Binder.getCallingUid(), null);
    }

    private void verifyIncomingOp(int op) {
        if (op >= 0 && op < AppOpsManager._NUM_OP) {
            return;
        }
        throw new IllegalArgumentException("Bad operation #" + op);
    }

    private Ops getOpsLocked(int uid, String packageName, boolean edit) {
        HashMap<String, Ops> pkgOps = mUidOps.get(uid);
        if (pkgOps == null) {
            if (!edit) {
                return null;
            }
            pkgOps = new HashMap<String, Ops>();
            mUidOps.put(uid, pkgOps);
        }
        if (uid == 0) {
            packageName = "root";
        } else if (uid == Process.SHELL_UID) {
            packageName = "com.android.shell";
        } else if (uid == Process.SYSTEM_UID && packageName == null) {
            packageName = "android";
        }
        Ops ops = pkgOps.get(packageName);
        if (ops == null) {
            if (!edit) {
                return null;
            }
            // This is the first time we have seen this package name under this uid,
            // so let's make sure it is valid.
            if (uid != 0 && uid != Process.SYSTEM_UID) {
                final long ident = Binder.clearCallingIdentity();
                try {
                    int pkgUid = -1;
                    try {
                        pkgUid = mContext.getPackageManager().getPackageUid(packageName,
                                UserHandle.getUserId(uid));
                    } catch (NameNotFoundException e) {
                        if ("media".equals(packageName)) {
                            pkgUid = Process.MEDIA_UID;
                        }
                    }
                    if (pkgUid != uid) {
                        // Oops!  The package name is not valid for the uid they are calling
                        // under.  Abort.
                        Slog.w(TAG, "Bad call: specified package " + packageName
                                + " under uid " + uid + " but it is really " + pkgUid);
                        return null;
                    }
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }
            ops = new Ops(packageName, uid);
            pkgOps.put(packageName, ops);
        }
        return ops;
    }

    private void scheduleWriteLocked() {
        if (!mWriteScheduled) {
            mWriteScheduled = true;
            mHandler.postDelayed(mWriteRunner, WRITE_DELAY);
        }
    }

    private void scheduleWriteNowLocked() {
        if (!mWriteScheduled) {
            mWriteScheduled = true;
        }
        mHandler.removeCallbacks(mWriteRunner);
        mHandler.post(mWriteRunner);
    }

    private Op getOpLocked(int code, int uid, String packageName, boolean edit) {
        Ops ops = getOpsLocked(uid, packageName, edit);
        if (ops == null) {
            return null;
        }
        return getOpLocked(ops, code, edit);
    }

    private Op getOpLocked(Ops ops, int code, boolean edit) {
        Op op = ops.get(code);
        if (op == null) {
            if (!edit) {
                return null;
            }
            int mode = getDefaultMode(code, ops.uid, ops.packageName);
            op = new Op(ops.uid, ops.packageName, code, mode);
            ops.put(code, op);
        }
        if (edit) {
            scheduleWriteLocked();
        }
        return op;
    }

    void readState() {
        synchronized (mFile) {
            synchronized (this) {
                FileInputStream stream;
                try {
                    stream = mFile.openRead();
                } catch (FileNotFoundException e) {
                    Slog.i(TAG, "No existing app ops " + mFile.getBaseFile() + "; starting empty");
                    return;
                }
                boolean success = false;
                try {
                    XmlPullParser parser = Xml.newPullParser();
                    parser.setInput(stream, null);
                    int type;
                    while ((type = parser.next()) != XmlPullParser.START_TAG
                            && type != XmlPullParser.END_DOCUMENT) {
                        ;
                    }

                    if (type != XmlPullParser.START_TAG) {
                        throw new IllegalStateException("no start tag found");
                    }

                    int outerDepth = parser.getDepth();
                    while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                            && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
                        if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                            continue;
                        }

                        String tagName = parser.getName();
                        if (tagName.equals("pkg")) {
                            readPackage(parser);
                        } else {
                            Slog.w(TAG, "Unknown element under <app-ops>: "
                                    + parser.getName());
                            XmlUtils.skipCurrentTag(parser);
                        }
                    }
                    success = true;
                } catch (IllegalStateException e) {
                    Slog.w(TAG, "Failed parsing " + e);
                } catch (NullPointerException e) {
                    Slog.w(TAG, "Failed parsing " + e);
                } catch (NumberFormatException e) {
                    Slog.w(TAG, "Failed parsing " + e);
                } catch (XmlPullParserException e) {
                    Slog.w(TAG, "Failed parsing " + e);
                } catch (IOException e) {
                    Slog.w(TAG, "Failed parsing " + e);
                } catch (IndexOutOfBoundsException e) {
                    Slog.w(TAG, "Failed parsing " + e);
                } finally {
                    if (!success) {
                        mUidOps.clear();
                    }
                    try {
                        stream.close();
                    } catch (IOException e) {
                    }
                }
            }
        }
    }

    void readPackage(XmlPullParser parser) throws NumberFormatException,
            XmlPullParserException, IOException {
        String pkgName = parser.getAttributeValue(null, "n");
        int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            String tagName = parser.getName();
            if (tagName.equals("uid")) {
                readUid(parser, pkgName);
            } else {
                Slog.w(TAG, "Unknown element under <pkg>: "
                        + parser.getName());
                XmlUtils.skipCurrentTag(parser);
            }
        }
    }

    void readUid(XmlPullParser parser, String pkgName) throws NumberFormatException,
            XmlPullParserException, IOException {
        int uid = Integer.parseInt(parser.getAttributeValue(null, "n"));
        int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            String tagName = parser.getName();
            if (tagName.equals("op")) {
                int code = Integer.parseInt(parser.getAttributeValue(null, "n"));
                // use op name string if it exists
                String codeNameStr = parser.getAttributeValue(null, "ns");
                if (codeNameStr != null) {
                    // returns OP_NONE if it could not be mapped
                    code = AppOpsManager.nameToOp(codeNameStr);
                }
                // skip op codes that are out of bounds
                if (code == AppOpsManager.OP_NONE
                        || code >= AppOpsManager._NUM_OP) {
                    continue;
                }
                int defaultMode = getDefaultMode(code, uid, pkgName);
                Op op = new Op(uid, pkgName, code, defaultMode);
                String mode = parser.getAttributeValue(null, "m");
                if (mode != null) {
                    op.mode = Integer.parseInt(mode);
                }
                String time = parser.getAttributeValue(null, "t");
                if (time != null) {
                    op.time = Long.parseLong(time);
                }
                time = parser.getAttributeValue(null, "r");
                if (time != null) {
                    op.rejectTime = Long.parseLong(time);
                }
                String dur = parser.getAttributeValue(null, "d");
                if (dur != null) {
                    op.duration = Integer.parseInt(dur);
                }
                String allowed = parser.getAttributeValue(null, "ac");
                if (allowed != null) {
                    op.allowedCount = Integer.parseInt(allowed);
                }
                String ignored = parser.getAttributeValue(null, "ic");
                if (ignored != null) {
                    op.ignoredCount = Integer.parseInt(ignored);
                }
                HashMap<String, Ops> pkgOps = mUidOps.get(uid);
                if (pkgOps == null) {
                    pkgOps = new HashMap<String, Ops>();
                    mUidOps.put(uid, pkgOps);
                }
                Ops ops = pkgOps.get(pkgName);
                if (ops == null) {
                    ops = new Ops(pkgName, uid);
                    pkgOps.put(pkgName, ops);
                }
                ops.put(op.op, op);
            } else {
                Slog.w(TAG, "Unknown element under <pkg>: "
                        + parser.getName());
                XmlUtils.skipCurrentTag(parser);
            }
        }
    }

    void writeState() {
        synchronized (mFile) {
            List<AppOpsManager.PackageOps> allOps = getPackagesForOps(null);

            FileOutputStream stream;
            try {
                stream = mFile.startWrite();
            } catch (IOException e) {
                Slog.w(TAG, "Failed to write state: " + e);
                return;
            }

            try {
                XmlSerializer out = new FastXmlSerializer();
                out.setOutput(stream, "utf-8");
                out.startDocument(null, true);
                out.startTag(null, "app-ops");

                if (allOps != null) {
                    String lastPkg = null;
                    for (int i=0; i<allOps.size(); i++) {
                        AppOpsManager.PackageOps pkg = allOps.get(i);
                        if (!pkg.getPackageName().equals(lastPkg)) {
                            if (lastPkg != null) {
                                out.endTag(null, "pkg");
                            }
                            lastPkg = pkg.getPackageName();
                            out.startTag(null, "pkg");
                            out.attribute(null, "n", lastPkg);
                        }
                        out.startTag(null, "uid");
                        out.attribute(null, "n", Integer.toString(pkg.getUid()));
                        List<AppOpsManager.OpEntry> ops = pkg.getOps();
                        for (int j=0; j<ops.size(); j++) {
                            AppOpsManager.OpEntry op = ops.get(j);
                            out.startTag(null, "op");
                            out.attribute(null, "n", Integer.toString(op.getOp()));
                            out.attribute(null, "ns", AppOpsManager.opToName(op.getOp()));
                            if (op.getMode() != getDefaultMode(op.getOp(),
                                    pkg.getUid(), pkg.getPackageName())) {
                                out.attribute(null, "m", Integer.toString(op.getMode()));
                            }
                            long time = op.getTime();
                            if (time != 0) {
                                out.attribute(null, "t", Long.toString(time));
                            }
                            time = op.getRejectTime();
                            if (time != 0) {
                                out.attribute(null, "r", Long.toString(time));
                            }
                            int dur = op.getDuration();
                            if (dur != 0) {
                                out.attribute(null, "d", Integer.toString(dur));
                            }
                            int allowed = op.getAllowedCount();
                            if (allowed != 0) {
                                out.attribute(null, "ac", Integer.toString(allowed));
                            }
                            int ignored = op.getIgnoredCount();
                            if (ignored != 0) {
                                out.attribute(null, "ic", Integer.toString(ignored));
                            }
                            out.endTag(null, "op");
                        }
                        out.endTag(null, "uid");
                    }
                    if (lastPkg != null) {
                        out.endTag(null, "pkg");
                    }
                }

                out.endTag(null, "app-ops");
                out.endDocument();
                mFile.finishWrite(stream);
            } catch (IOException e) {
                Slog.w(TAG, "Failed to write state, restoring backup.", e);
                mFile.failWrite(stream);
            }
        }
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                != PackageManager.PERMISSION_GRANTED) {
            pw.println("Permission Denial: can't dump ApOps service from from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid());
            return;
        }

        synchronized (this) {
            pw.println("Current AppOps Service state:");
            final long now = System.currentTimeMillis();
            boolean needSep = false;
            if (mOpModeWatchers.size() > 0) {
                needSep = true;
                pw.println("  Op mode watchers:");
                for (int i=0; i<mOpModeWatchers.size(); i++) {
                    pw.print("    Op "); pw.print(AppOpsManager.opToName(mOpModeWatchers.keyAt(i)));
                    pw.println(":");
                    ArrayList<Callback> callbacks = mOpModeWatchers.valueAt(i);
                    for (int j=0; j<callbacks.size(); j++) {
                        pw.print("      #"); pw.print(j); pw.print(": ");
                        pw.println(callbacks.get(j));
                    }
                }
            }
            if (mPackageModeWatchers.size() > 0) {
                needSep = true;
                pw.println("  Package mode watchers:");
                for (int i=0; i<mPackageModeWatchers.size(); i++) {
                    pw.print("    Pkg "); pw.print(mPackageModeWatchers.keyAt(i));
                    pw.println(":");
                    ArrayList<Callback> callbacks = mPackageModeWatchers.valueAt(i);
                    for (int j=0; j<callbacks.size(); j++) {
                        pw.print("      #"); pw.print(j); pw.print(": ");
                        pw.println(callbacks.get(j));
                    }
                }
            }
            if (mModeWatchers.size() > 0) {
                needSep = true;
                pw.println("  All mode watchers:");
                for (int i=0; i<mModeWatchers.size(); i++) {
                    pw.print("    "); pw.print(mModeWatchers.keyAt(i));
                    pw.print(" -> "); pw.println(mModeWatchers.valueAt(i));
                }
            }
            if (mClients.size() > 0) {
                needSep = true;
                pw.println("  Clients:");
                for (int i=0; i<mClients.size(); i++) {
                    pw.print("    "); pw.print(mClients.keyAt(i)); pw.println(":");
                    ClientState cs = mClients.valueAt(i);
                    pw.print("      "); pw.println(cs);
                    if (cs.mStartedOps != null && cs.mStartedOps.size() > 0) {
                        pw.println("      Started ops:");
                        for (int j=0; j<cs.mStartedOps.size(); j++) {
                            Op op = cs.mStartedOps.get(j);
                            pw.print("        "); pw.print("uid="); pw.print(op.uid);
                            pw.print(" pkg="); pw.print(op.packageName);
                            pw.print(" op="); pw.println(AppOpsManager.opToName(op.op));
                        }
                    }
                }
            }
            if (needSep) {
                pw.println();
            }
            for (int i=0; i<mUidOps.size(); i++) {
                pw.print("  Uid "); UserHandle.formatUid(pw, mUidOps.keyAt(i)); pw.println(":");
                HashMap<String, Ops> pkgOps = mUidOps.valueAt(i);
                for (Ops ops : pkgOps.values()) {
                    pw.print("    Package "); pw.print(ops.packageName); pw.println(":");
                    for (int j=0; j<ops.size(); j++) {
                        Op op = ops.valueAt(j);
                        pw.print("      "); pw.print(AppOpsManager.opToName(op.op));
                        pw.print(": mode="); pw.print(op.mode);
                        if (op.time != 0) {
                            pw.print("; time="); TimeUtils.formatDuration(now-op.time, pw);
                            pw.print(" ago");
                        }
                        if (op.rejectTime != 0) {
                            pw.print("; rejectTime="); TimeUtils.formatDuration(now-op.rejectTime, pw);
                            pw.print(" ago");
                        }
                        if (op.duration == -1) {
                            pw.println(" (running)");
                        } else {
                            pw.print("; duration=");
                                    TimeUtils.formatDuration(op.duration, pw);
                                    pw.println();
                        }
                    }
                }
            }
        }
    }

    @Override
    public List<Integer> getPrivacyGuardOpsForPackage(String packageName) {
        PackageInfo pkgInfo;
        List<Integer> ops = new ArrayList<Integer>();
        try {
            pkgInfo = mContext.getPackageManager()
                .getPackageInfo(packageName, PackageManager.GET_PERMISSIONS);
        } catch (NameNotFoundException e) {
            return ops;
        }
        // we need to get all relevant permissions of the package.
        // Only calling getOpsForPackage does not return ops
        // with AppOpsManager.MODE_ALLOWED which never got
        // granted and do not show up in Ops statistics
        final String[] requestedPermissions = pkgInfo.requestedPermissions;
        if (requestedPermissions != null) {
            for (String requested : requestedPermissions) {
                int curOp = AppOpsManager.getPrivacyGuardOp(requested);
                boolean duplicate = false;
                // check for duplicates
                for (int op : ops) {
                    if (op == curOp) {
                        duplicate = true;
                    }
                }
                if (curOp != AppOpsManager.OP_NONE && !duplicate) {
                    ops.add(curOp);
                }
            }
        }
        return ops;
    }

    @Override
    public int getPrivacyGuardSettingForPackage(int uid, String packageName) {
        int privacyGuardState;
        boolean pgDetect;
        boolean isCustomOpPresent = false;

        List<AppOpsManager.PackageOps> packageOps =
            getOpsForPackage(uid, packageName, null);
        List<Integer> privacyGuardOps = getPrivacyGuardOpsForPackage(packageName);
        List<Integer> privacyGuardOpsHelper = new ArrayList<Integer>(privacyGuardOps);

        // get disabled Ops and check for custom Op changes
        if (packageOps != null) {
            for (AppOpsManager.OpEntry op : packageOps.get(0).getOps()) {
                pgDetect = false;
                if (checkOperation(op.getOp(), uid, packageName)
                    == AppOpsManager.MODE_IGNORED) {
                    for (int pgOp : privacyGuardOps) {
                        if (AppOpsManager.opToSwitch(op.getOp()) == pgOp) {
                            privacyGuardOpsHelper.remove((Integer) pgOp);
                            pgDetect = true;
                            break;
                        }
                    }
                    if (!pgDetect) {
                        isCustomOpPresent = true;
                    }
                }
            }
        }

        // get privacy guard state
        if (!privacyGuardOps.isEmpty()
            && privacyGuardOps.size() != privacyGuardOpsHelper.size()) {
            if (privacyGuardOpsHelper.isEmpty()) {
                privacyGuardState = AppOpsManager.PRIVACY_GUARD_ENABLED;
            } else {
                privacyGuardState = AppOpsManager.PRIVACY_GUARD_CUSTOM;
            }
        } else {
            privacyGuardState = AppOpsManager.PRIVACY_GUARD_DISABLED;
        }

        // return current mode
        switch (privacyGuardState) {
            case AppOpsManager.PRIVACY_GUARD_ENABLED:
                if (isCustomOpPresent) {
                    return AppOpsManager.PRIVACY_GUARD_ENABLED_PLUS;
                } else {
                    return AppOpsManager.PRIVACY_GUARD_ENABLED;
                }
            case AppOpsManager.PRIVACY_GUARD_CUSTOM:
                if (isCustomOpPresent) {
                    return AppOpsManager.PRIVACY_GUARD_CUSTOM_PLUS;
                } else {
                    return AppOpsManager.PRIVACY_GUARD_CUSTOM;
                }
            case AppOpsManager.PRIVACY_GUARD_DISABLED:
                if (isCustomOpPresent) {
                    return AppOpsManager.PRIVACY_GUARD_DISABLED_PLUS;
                } else {
                    return AppOpsManager.PRIVACY_GUARD_DISABLED;
                }
        }
        return AppOpsManager.PRIVACY_GUARD_DISABLED;
    }

    @Override
<<<<<<< HEAD
    public void setPrivacyGuardSettingForPackage(int uid, String packageName,
            boolean state, boolean forceAll) {
        List<Integer> switchOps;
        if (!forceAll) {
            // retrieve specific privacy guard permissions
            switchOps = getPrivacyGuardOpsForPackage(packageName);
        } else {
            // if user want to enable all permissions on the package
            // we need to retrieve the disabled ops.
            // All ops are retrieved via getOpsForPackage
            // which are flagged with AppOpsManager.MODE_IGNORED
            switchOps = new ArrayList<Integer>();
            List<AppOpsManager.PackageOps> packageOps =
                getOpsForPackage(uid, packageName, null);
            if (packageOps != null) {
                for (AppOpsManager.OpEntry op : packageOps.get(0).getOps()) {
                    if (checkOperation(op.getOp(), uid, packageName)
                        == AppOpsManager.MODE_IGNORED) {
                        switchOps.add(AppOpsManager.opToSwitch(op.getOp()));
                    }
                }
            }
        }

        for (int op : switchOps) {
            setMode(op, uid, packageName, state
                    ? AppOpsManager.MODE_IGNORED : AppOpsManager.MODE_ALLOWED);
        }
    }

    public void setPrivacyGuardSettingForPackage(int uid, String packageName, boolean state) {
        for (int op : PRIVACY_GUARD_OP_STATES) {
            int switchOp = AppOpsManager.opToSwitch(op);
            setMode(switchOp, uid, packageName, state
                    ? AppOpsManager.MODE_ASK : AppOpsManager.MODE_ALLOWED);
        }
    }

    @Override
    public void resetCounters() {
        mContext.enforcePermission(android.Manifest.permission.UPDATE_APP_OPS_STATS,
                Binder.getCallingPid(), Binder.getCallingUid(), null);
        synchronized (this) {
            for (int i=0; i<mUidOps.size(); i++) {
                HashMap<String, Ops> packages = mUidOps.valueAt(i);
                for (Map.Entry<String, Ops> ent : packages.entrySet()) {
                    String packageName = ent.getKey();
                    Ops pkgOps = ent.getValue();
                    for (int j=0; j<pkgOps.size(); j++) {
                        Op curOp = pkgOps.valueAt(j);
                        curOp.allowedCount = 0;
                        curOp.ignoredCount = 0;
                    }
                }
            }
            // ensure the counter reset persists
            scheduleWriteNowLocked();
        }
    }

    private int getDefaultMode(int code, int uid, String packageName) {
        return AppOpsManager.opToDefaultMode(code, isStrict(code, uid,
            packageName));
    }

    private boolean isStrict(int code, int uid, String packageName) {
        if (!mStrictEnable)
            return false;

        return ((uid > Process.FIRST_APPLICATION_UID) &&
            !isInWhitelist(packageName));
    }

    private boolean isInWhitelist(String packageName) {
        return mWhitelist.contains(packageName);
    }

    private Result askOperationLocked(int code, int uid, String packageName,
                                      Op op) {
        Result result = new Result();
        final long origId = Binder.clearCallingIdentity();
        Message msg = Message.obtain();
        msg.what = SHOW_PERMISSION_DIALOG;
        HashMap data = new HashMap();
        data.put("result", result);
        data.put("code", code);
        data.put("packageName", packageName);
        data.put("op", op);
        data.put("uid", uid);
        msg.obj = data;
        mHandler.sendMessage(msg);
        Binder.restoreCallingIdentity(origId);
        return result;
    }

    private void printOperationLocked(Op op, int mode, String operation) {
        if(op != null) {
            int switchCode = AppOpsManager.opToSwitch(op.op);
            if (mode == AppOpsManager.MODE_IGNORED) {
                if (DEBUG) Log.d(TAG, operation + ": reject #" + mode + " for code "
                        + switchCode + " (" + op.op + ") uid " + op.uid + " package "
                        + op.packageName);
            } else if (mode == AppOpsManager.MODE_ALLOWED) {
                if (DEBUG) Log.d(TAG, operation + ": allowing code " + op.op + " uid "
                    + op.uid
                    + " package " + op.packageName);
            }
        }
    }

    private void recordOperationLocked(int code, int uid, String packageName,
                                    int mode) {
        Op op = getOpLocked(code, uid, packageName, false);
        if(op != null) {
            if(op.noteOpCount != 0)
                printOperationLocked(op, mode, "noteOperartion");
            if(op.startOpCount != 0)
                printOperationLocked(op, mode, "startOperation");
            int switchCode = AppOpsManager.opToSwitch(op.op);
            if (mode == AppOpsManager.MODE_IGNORED) {
                op.rejectTime = System.currentTimeMillis();
            } else if (mode == AppOpsManager.MODE_ALLOWED) {
                if(op.noteOpCount != 0) {
                    op.time = System.currentTimeMillis();
                    op.rejectTime = 0;
                }
                if(op.startOpCount != 0) {
                    if(op.nesting == 0) {
                        op.time = System.currentTimeMillis();
                        op.rejectTime = 0;
                        op.duration = -1;
                    }
                    op.nesting = op.nesting + op.startOpCount;
                    while(op.mClientTokens.size() != 0) {
                        IBinder clientToken = op.mClientTokens.get(0);
                        ClientState client = mClients.get(clientToken);
                        if (client != null) {
                            if (client.mStartedOps != null) {
                                client.mStartedOps.add(op);
                            }
                        }
                        op.mClientTokens.remove(0);
                    }
                }
            }
            op.startOpCount = 0;
            op.noteOpCount = 0;
        }
    }

    public void notifyOperation(int code, int uid, String packageName, int mode,
                                boolean remember) {
        verifyIncomingUid(uid);
        verifyIncomingOp(code);
        ArrayList<Callback> repCbs = null;
        int switchCode = AppOpsManager.opToSwitch(code);
        synchronized (this) {
            recordOperationLocked(code, uid, packageName, mode);
            Op op = getOpLocked(switchCode, uid, packageName, true);
            if (op != null) {
                // Send result to all waiting client
                if( op.dialogResult.mDialog != null) {
                    op.dialogResult.notifyAll(mode);
                    op.dialogResult.mDialog = null;
                }
                if (remember && op.mode != mode) {
                    op.mode = mode;
                    ArrayList<Callback> cbs = mOpModeWatchers.get(switchCode);
                    if (cbs != null) {
                        if (repCbs == null) {
                            repCbs = new ArrayList<Callback>();
                        }
                        repCbs.addAll(cbs);
                    }
                    cbs = mPackageModeWatchers.get(packageName);
                    if (cbs != null) {
                        if (repCbs == null) {
                            repCbs = new ArrayList<Callback>();
                        }
                        repCbs.addAll(cbs);
                    }
                    if (mode == getDefaultMode(op.op, op.uid, op.packageName)) {
                        // If going into the default mode, prune this op
                        // if there is nothing else interesting in it.
                        pruneOp(op, uid, packageName);
                    }
                    scheduleWriteNowLocked();
                }
            }
        }
        if (repCbs != null) {
            for (int i=0; i<repCbs.size(); i++) {
                try {
                    repCbs.get(i).mCallback.opChanged(switchCode, packageName);
                } catch (RemoteException e) {
                }
            }
        }
    }

    final ArrayList<String> mWhitelist = new ArrayList<String>();

    void readWhitelist() {
        // Read if whitelist file provided
        String whitelistFileName = SystemProperties.get(WHITELIST_FILE);
        if(!mStrictEnable || "".equals(whitelistFileName)) {
            return;
        }
        final File whitelistFile = new File(whitelistFileName);
        final AtomicFile mWhitelistFile = new AtomicFile(whitelistFile);
        synchronized (mWhitelistFile) {
            synchronized (this) {
                FileInputStream stream;
                try {
                    stream = mWhitelistFile.openRead();
                } catch (FileNotFoundException e) {
                    Slog.i(TAG, "No existing app ops whitelist " +
                        mWhitelistFile.getBaseFile());
                    return;
                }
                boolean success = false;
                try {
                    XmlPullParser parser = Xml.newPullParser();
                    parser.setInput(stream, null);
                    int type;
                    while ((type = parser.next()) != XmlPullParser.START_TAG
                            && type != XmlPullParser.END_DOCUMENT) {
                        ;
                    }

                    if (type != XmlPullParser.START_TAG) {
                        throw new IllegalStateException("no start tag found");
                    }

                    int outerDepth = parser.getDepth();
                    while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                            && (type != XmlPullParser.END_TAG ||
                            parser.getDepth() > outerDepth)) {
                        if (type == XmlPullParser.END_TAG ||
                            type == XmlPullParser.TEXT) {
                            continue;
                        }

                        String tagName = parser.getName();
                        if (tagName.equals("pkg")) {
                            String pkgName = parser.getAttributeValue(null, "name");
                            mWhitelist.add(pkgName);
                        } else {
                            Slog.w(TAG, "Unknown element under <whitelist-pkgs>: "
                                    + parser.getName());
                            XmlUtils.skipCurrentTag(parser);
                        }
                    }
                    success = true;
                } catch (IllegalStateException e) {
                    Slog.w(TAG, "Failed parsing " + e);
                } catch (NullPointerException e) {
                    Slog.w(TAG, "Failed parsing " + e);
                } catch (NumberFormatException e) {
                    Slog.w(TAG, "Failed parsing " + e);
                } catch (XmlPullParserException e) {
                    Slog.w(TAG, "Failed parsing " + e);
                } catch (IOException e) {
                    Slog.w(TAG, "Failed parsing " + e);
                } catch (IndexOutOfBoundsException e) {
                    Slog.w(TAG, "Failed parsing " + e);
                } finally {
                    if (!success) {
                        mWhitelist.clear();
                    }
                    try {
                        stream.close();
                    } catch (IOException e) {
                    }
                }
            }
        }
    }
}
