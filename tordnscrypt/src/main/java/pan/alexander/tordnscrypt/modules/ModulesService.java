package pan.alexander.tordnscrypt.modules;
/*
    This file is part of InviZible Pro.

    InviZible Pro is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    InviZible Pro is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with InviZible Pro.  If not, see <http://www.gnu.org/licenses/>.

    Copyright 2019-2021 by Garmatin Oleksandr invizible.soft@gmail.com
*/

import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;

import androidx.preference.PreferenceManager;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import dagger.Lazy;
import pan.alexander.tordnscrypt.App;
import pan.alexander.tordnscrypt.R;
import pan.alexander.tordnscrypt.arp.ArpScanner;
import pan.alexander.tordnscrypt.domain.connection_checker.ConnectionCheckerInteractor;
import pan.alexander.tordnscrypt.domain.preferences.PreferenceRepository;
import pan.alexander.tordnscrypt.patches.Patch;
import pan.alexander.tordnscrypt.settings.PathVars;
import pan.alexander.tordnscrypt.utils.ap.InternetSharingChecker;
import pan.alexander.tordnscrypt.utils.executors.CachedExecutor;
import pan.alexander.tordnscrypt.utils.Utils;
import pan.alexander.tordnscrypt.utils.wakelock.WakeLocksManager;
import pan.alexander.tordnscrypt.utils.enums.OperationMode;
import pan.alexander.tordnscrypt.utils.filemanager.FileManager;
import pan.alexander.tordnscrypt.vpn.service.ServiceVPNHelper;

import static pan.alexander.tordnscrypt.TopFragment.DNSCryptVersion;
import static pan.alexander.tordnscrypt.TopFragment.TorVersion;
import static pan.alexander.tordnscrypt.modules.ModulesServiceActions.actionDismissNotification;
import static pan.alexander.tordnscrypt.modules.ModulesServiceActions.actionRecoverService;
import static pan.alexander.tordnscrypt.modules.ModulesServiceActions.actionRestartDnsCrypt;
import static pan.alexander.tordnscrypt.modules.ModulesServiceActions.actionRestartITPD;
import static pan.alexander.tordnscrypt.modules.ModulesServiceActions.actionRestartTor;
import static pan.alexander.tordnscrypt.modules.ModulesServiceActions.actionRestartTorFull;
import static pan.alexander.tordnscrypt.modules.ModulesServiceActions.actionStartDnsCrypt;
import static pan.alexander.tordnscrypt.modules.ModulesServiceActions.actionStartITPD;
import static pan.alexander.tordnscrypt.modules.ModulesServiceActions.actionStartTor;
import static pan.alexander.tordnscrypt.modules.ModulesServiceActions.actionStopDnsCrypt;
import static pan.alexander.tordnscrypt.modules.ModulesServiceActions.actionStopITPD;
import static pan.alexander.tordnscrypt.modules.ModulesServiceActions.actionStopService;
import static pan.alexander.tordnscrypt.modules.ModulesServiceActions.actionStopServiceForeground;
import static pan.alexander.tordnscrypt.modules.ModulesServiceActions.actionStopTor;
import static pan.alexander.tordnscrypt.modules.ModulesServiceActions.clearIptablesCommandsHash;
import static pan.alexander.tordnscrypt.modules.ModulesServiceActions.extraLoop;
import static pan.alexander.tordnscrypt.modules.ModulesServiceActions.slowdownLoop;
import static pan.alexander.tordnscrypt.modules.ModulesServiceActions.speedupLoop;
import static pan.alexander.tordnscrypt.modules.ModulesServiceActions.startArpScanner;
import static pan.alexander.tordnscrypt.modules.ModulesServiceActions.stopArpScanner;
import static pan.alexander.tordnscrypt.utils.preferences.PreferenceKeys.VPN_SERVICE_ENABLED;
import static pan.alexander.tordnscrypt.utils.root.RootExecService.LOG_TAG;
import static pan.alexander.tordnscrypt.utils.enums.ModuleState.RESTARTING;
import static pan.alexander.tordnscrypt.utils.enums.ModuleState.RUNNING;
import static pan.alexander.tordnscrypt.utils.enums.ModuleState.STARTING;
import static pan.alexander.tordnscrypt.utils.enums.ModuleState.STOPPED;
import static pan.alexander.tordnscrypt.utils.enums.OperationMode.ROOT_MODE;
import static pan.alexander.tordnscrypt.utils.enums.OperationMode.VPN_MODE;

import javax.inject.Inject;

public class ModulesService extends Service {
    public static final int DEFAULT_NOTIFICATION_ID = 101102;

    public static boolean serviceIsRunning = false;

    private final static int TIMER_HIGH_SPEED = 1000;
    private final static int TIMER_LOW_SPEED = 30000;

    public static final String DNSCRYPT_KEYWORD = "checkDNSRunning";
    public static final String TOR_KEYWORD = "checkTrRunning";
    public static final String ITPD_KEYWORD = "checkITPDRunning";

    @Inject
    public Lazy<PreferenceRepository> preferenceRepository;
    @Inject
    public Lazy<ConnectionCheckerInteractor> internetCheckerInteractor;

    private static WakeLocksManager wakeLocksManager;

    ModulesBroadcastReceiver modulesBroadcastReceiver;

    @Inject
    public volatile Lazy<Handler> handler;
    @Inject
    public Lazy<PathVars> pathVars;
    @Inject
    public CachedExecutor cachedExecutor;

    private final ModulesStatus modulesStatus = ModulesStatus.getInstance();

    private NotificationManager systemNotificationManager;
    private ScheduledExecutorService checkModulesThreadsTimer;
    private ScheduledFuture<?> scheduledFuture;
    private int timerPeriod = TIMER_HIGH_SPEED;
    private ModulesStateLoop checkModulesStateTask;
    private ModulesKiller modulesKiller;
    private UsageStatistic usageStatistic;
    private ArpScanner arpScanner;

    public ModulesService() {
    }

    @Override
    public void onCreate() {
        super.onCreate();

        systemNotificationManager = (NotificationManager) this.getSystemService(NOTIFICATION_SERVICE);

        usageStatistic = new UsageStatistic(this);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            String title = getString(R.string.app_name);
            String message = getString(R.string.notification_text);
            if (usageStatistic.isStatisticAllowed()) {
                title = usageStatistic.getTitle();
                message = usageStatistic.getMessage(System.currentTimeMillis());
            }

            ModulesServiceNotificationManager serviceNotificationManager = new ModulesServiceNotificationManager(this, systemNotificationManager, UsageStatisticKt.getStartTime());
            serviceNotificationManager.sendNotification(title, message);
        }

        App.getInstance().getDaggerComponent().inject(this);

        serviceIsRunning = true;

        modulesKiller = new ModulesKiller(this, pathVars.get());

        startModulesThreadsTimer();

        startArpScanner();
    }


    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        if (intent != null && Objects.equals(intent.getAction(), actionStopServiceForeground)) {
            stopModulesServiceForeground();
        }

        boolean showNotification;
        if (intent != null) {
            showNotification = intent.getBooleanExtra("showNotification", true);
        } else {
            showNotification = Utils.INSTANCE.isShowNotification(this);
        }

        if (showNotification) {

            String title = getString(R.string.app_name);
            String message = getString(R.string.notification_text);
            if (usageStatistic.isStatisticAllowed()) {
                title = usageStatistic.getTitle();
                message = usageStatistic.getMessage(System.currentTimeMillis());
            }

            ModulesServiceNotificationManager notification = new ModulesServiceNotificationManager(this, systemNotificationManager, UsageStatisticKt.getStartTime());
            notification.sendNotification(title, message);
            usageStatistic.setServiceNotification(notification);

            if (usageStatistic.isStatisticAllowed()) {
                usageStatistic.startUpdate();
            }
        }

        if (intent != null && Objects.equals(intent.getAction(), actionStopServiceForeground)) {
            stopModulesServiceForeground(startId);
            return START_NOT_STICKY;
        }

        if (intent == null) {
            stopService(startId);
            return START_NOT_STICKY;
        }

        String action = intent.getAction();

        if (action == null) {
            stopService(startId);
            return START_NOT_STICKY;
        }

        manageWakelocks();

        switch (action) {
            case actionStartDnsCrypt:
                startDNSCrypt();
                break;
            case actionStartTor:
                startTor();
                break;
            case actionStartITPD:
                startITPD();
                break;
            case actionStopDnsCrypt:
                stopDNSCrypt();
                break;
            case actionStopTor:
                stopTor();
                break;
            case actionStopITPD:
                stopITPD();
                break;
            case actionRestartDnsCrypt:
                restartDNSCrypt();
                break;
            case actionRestartTor:
                restartTor();
                break;
            case actionRestartTorFull:
                restartTorFull();
                break;
            case actionRestartITPD:
                restartITPD();
                break;
            case actionDismissNotification:
                dismissNotification(startId);
                break;
            case actionRecoverService:
                setAllModulesStateStopped();
                break;
            case speedupLoop:
                speedupTimer();
                break;
            case slowdownLoop:
                slowdownTimer();
                break;
            case extraLoop:
                makeExtraLoop();
                break;
            case actionStopService:
                stopModulesService();
                return START_NOT_STICKY;
            case startArpScanner:
                startArpScanner();
                break;
            case stopArpScanner:
                stopArpScanner();
                break;
            case clearIptablesCommandsHash:
                clearIptablesCommandsSavedHash();
                break;
        }

        setBroadcastReceiver();

        return START_REDELIVER_INTENT;

    }

    private void startDNSCrypt() {

        if (modulesStatus.getDnsCryptState() == STOPPED) {
            modulesStatus.setDnsCryptState(STARTING);
        }

        new Thread(() -> {

            checkModulesConfigPatches();

            if (!modulesStatus.isUseModulesWithRoot()) {
                Thread previousDnsCryptThread = modulesKiller.getDnsCryptThread();

                if (previousDnsCryptThread != null && previousDnsCryptThread.isAlive()) {
                    changeDNSCryptStatus(previousDnsCryptThread);
                    return;
                }
            }

            try {
                Thread previousDnsCryptThread = checkPreviouslyRunningDNSCryptModule();

                if (previousDnsCryptThread != null && previousDnsCryptThread.isAlive()) {
                    changeDNSCryptStatus(previousDnsCryptThread);
                    return;
                }

                if (stopDNSCryptIfPortIsBusy()) {
                    changeDNSCryptStatus(modulesKiller.getDnsCryptThread());
                    return;
                }

                cleanLogFileNoRootMethod(pathVars.get().getAppDataDir() + "/logs/DnsCrypt.log",
                        ModulesService.this.getResources().getString(R.string.tvDNSDefaultLog) + " " + DNSCryptVersion);

                ModulesStarterHelper modulesStarterHelper = new ModulesStarterHelper(
                        ModulesService.this.getApplicationContext(), handler.get()
                );
                Thread dnsCryptThread = new Thread(modulesStarterHelper.getDNSCryptStarterRunnable());
                dnsCryptThread.setName("DNSCryptThread");
                dnsCryptThread.setDaemon(false);
                try {
                    dnsCryptThread.setPriority(Thread.NORM_PRIORITY);
                } catch (SecurityException e) {
                    Log.e(LOG_TAG, "ModulesService startDNSCrypt exception " + e.getMessage() + " " + e.getCause());
                }
                dnsCryptThread.start();

                changeDNSCryptStatus(dnsCryptThread);

            } catch (Exception e) {
                Log.e(LOG_TAG, "DnsCrypt was unable to start " + e.getMessage());
                if (handler != null) {
                    handler.get().post(() -> Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show());
                }
            }

        }).start();
    }

    private Thread checkPreviouslyRunningDNSCryptModule() {

        if (modulesStatus.isUseModulesWithRoot()) {
            return null;
        }

        Thread result = null;

        try {
            if (modulesStatus.getDnsCryptState() != RESTARTING) {
                result = findThreadByName("DNSCryptThread");
            }
        } catch (Exception e) {
            Log.e(LOG_TAG, "checkPreviouslyRunningDNSCryptModule exception " + e.getMessage());
        }

        return result;
    }

    private void changeDNSCryptStatus(final Thread dnsCryptThread) {

        makeDelay(2);

        if (modulesStatus.isUseModulesWithRoot() || dnsCryptThread.isAlive()) {
            modulesStatus.setDnsCryptState(RUNNING);

            if (modulesKiller != null && !modulesStatus.isUseModulesWithRoot()) {
                modulesKiller.setDnsCryptThread(dnsCryptThread);
            }

            if (checkModulesStateTask != null && !modulesStatus.isUseModulesWithRoot()) {
                checkModulesStateTask.setDnsCryptThread(dnsCryptThread);
            }

            checkInternetConnection();
        } else {
            modulesStatus.setDnsCryptState(STOPPED);
        }
    }

    private boolean stopDNSCryptIfPortIsBusy() {
        if (isNotAvailable(pathVars.get().getDNSCryptPort())) {
            try {
                modulesStatus.setDnsCryptState(RESTARTING);

                Thread killerThread = new Thread(modulesKiller.getDNSCryptKillerRunnable());
                killerThread.start();

                while (killerThread.isAlive()) {
                    killerThread.join();
                }

                makeDelay(5);

                if (modulesStatus.getDnsCryptState() == RUNNING) {
                    return true;
                }

                modulesStatus.setDnsCryptState(STARTING);

            } catch (InterruptedException e) {
                Log.e(LOG_TAG, "ModulesService restartDNSCrypt join interrupted!");
            }
        }
        return false;
    }

    private void startTor() {

        if (modulesStatus.getTorState() == STOPPED) {
            modulesStatus.setTorState(STARTING);
        }

        new Thread(() -> {

            checkModulesConfigPatches();

            if (!modulesStatus.isUseModulesWithRoot()) {
                Thread previousTorThread = modulesKiller.getTorThread();

                if (previousTorThread != null && previousTorThread.isAlive()) {
                    changeTorStatus(previousTorThread);
                    return;
                }
            }

            try {
                Thread previousTorThread = checkPreviouslyRunningTorModule();

                if (previousTorThread != null && previousTorThread.isAlive()) {
                    changeTorStatus(previousTorThread);
                    return;
                }

                if (stopTorIfPortsIsBusy()) {
                    changeTorStatus(modulesKiller.getTorThread());
                    return;
                }

                cleanLogFileNoRootMethod(pathVars.get().getAppDataDir() + "/logs/Tor.log",
                        ModulesService.this.getResources().getString(R.string.tvTorDefaultLog) + " " + TorVersion);

                ModulesStarterHelper modulesStarterHelper = new ModulesStarterHelper(
                        ModulesService.this.getApplicationContext(), handler.get()
                );
                Thread torThread = new Thread(modulesStarterHelper.getTorStarterRunnable());
                torThread.setName("TorThread");
                torThread.setDaemon(false);
                try {
                    torThread.setPriority(Thread.NORM_PRIORITY);
                } catch (SecurityException e) {
                    Log.e(LOG_TAG, "ModulesService startTor exception " + e.getMessage() + " " + e.getCause());
                }
                torThread.start();

                changeTorStatus(torThread);
            } catch (Exception e) {
                Log.e(LOG_TAG, "Tor was unable to startRefreshModulesStatus: " + e.getMessage());
                if (handler != null) {
                    handler.get().post(() -> Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show());
                }
            }

        }).start();

    }

    private Thread checkPreviouslyRunningTorModule() {

        if (modulesStatus.isUseModulesWithRoot()) {
            return null;
        }

        Thread result = null;

        try {
            if (modulesStatus.getTorState() != RESTARTING) {
                result = findThreadByName("TorThread");
            }
        } catch (Exception e) {
            Log.e(LOG_TAG, "checkPreviouslyRunningTorModule exception " + e.getMessage());
        }

        return result;
    }

    private void changeTorStatus(final Thread torThread) {

        makeDelay(2);

        if (modulesStatus.isUseModulesWithRoot() || torThread.isAlive()) {
            modulesStatus.setTorState(RUNNING);

            if (modulesKiller != null && !modulesStatus.isUseModulesWithRoot()) {
                modulesKiller.setTorThread(torThread);
            }

            if (checkModulesStateTask != null && !modulesStatus.isUseModulesWithRoot()) {
                checkModulesStateTask.setTorThread(torThread);
            }

            checkInternetConnection();
        } else {
            modulesStatus.setTorState(STOPPED);
        }
    }

    private boolean stopTorIfPortsIsBusy() {
        boolean stopRequired = isNotAvailable(pathVars.get().getTorDNSPort())
                || isNotAvailable(pathVars.get().getTorSOCKSPort())
                || isNotAvailable(pathVars.get().getTorTransPort())
                || isNotAvailable(pathVars.get().getTorHTTPTunnelPort());

        if (stopRequired) {
            try {
                modulesStatus.setTorState(RESTARTING);

                Thread killerThread = new Thread(modulesKiller.getTorKillerRunnable());
                killerThread.start();

                while (killerThread.isAlive()) {
                    killerThread.join();
                }

                makeDelay(5);

                if (modulesStatus.getTorState() == RUNNING) {
                    return true;
                }

                modulesStatus.setTorState(STARTING);

            } catch (InterruptedException e) {
                Log.e(LOG_TAG, "ModulesService restartTor join interrupted!");
            }
        }
        return false;
    }


    private void startITPD() {

        if (modulesStatus.getItpdState() == STOPPED) {
            modulesStatus.setItpdState(STARTING);
        }

        new Thread(() -> {

            checkModulesConfigPatches();

            if (!modulesStatus.isUseModulesWithRoot()) {
                Thread previousITPDThread = modulesKiller.getItpdThread();

                if (previousITPDThread != null && previousITPDThread.isAlive()) {
                    changeITPDStatus(previousITPDThread);
                    return;
                }
            }

            try {
                Thread previousITPDThread = checkPreviouslyRunningITPDModule();

                if (previousITPDThread != null && previousITPDThread.isAlive()) {
                    changeITPDStatus(previousITPDThread);
                    return;
                }

                if (stopITPDIfPortsIsBusy()) {
                    changeITPDStatus(modulesKiller.getItpdThread());
                    return;
                }

                cleanLogFileNoRootMethod(pathVars.get().getAppDataDir() + "/logs/i2pd.log", "");

                ModulesStarterHelper modulesStarterHelper = new ModulesStarterHelper(
                        ModulesService.this.getApplicationContext(), handler.get()
                );
                Thread itpdThread = new Thread(modulesStarterHelper.getITPDStarterRunnable());
                itpdThread.setName("ITPDThread");
                itpdThread.setDaemon(false);
                try {
                    itpdThread.setPriority(Thread.NORM_PRIORITY);
                } catch (SecurityException e) {
                    Log.e(LOG_TAG, "ModulesService startITPD exception " + e.getMessage() + " " + e.getCause());
                }
                itpdThread.start();

                changeITPDStatus(itpdThread);
            } catch (Exception e) {
                Log.e(LOG_TAG, "I2PD was unable to startRefreshModulesStatus: " + e.getMessage());
                if (handler != null) {
                    handler.get().post(() -> Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show());
                }
            }

        }).start();
    }

    private Thread checkPreviouslyRunningITPDModule() {

        if (modulesStatus.isUseModulesWithRoot()) {
            return null;
        }

        Thread result = null;

        try {
            if (modulesStatus.getItpdState() != RESTARTING) {
                result = findThreadByName("ITPDThread");
            }
        } catch (Exception e) {
            Log.e(LOG_TAG, "checkPreviouslyRunningITPDModule exception " + e.getMessage());
        }

        return result;
    }

    private void changeITPDStatus(final Thread itpdThread) {

        makeDelay(3);

        if (modulesStatus.isUseModulesWithRoot() || itpdThread.isAlive()) {
            modulesStatus.setItpdState(RUNNING);

            if (modulesKiller != null && !modulesStatus.isUseModulesWithRoot()) {
                modulesKiller.setItpdThread(itpdThread);
            }

            if (checkModulesStateTask != null && !modulesStatus.isUseModulesWithRoot()) {
                checkModulesStateTask.setItpdThread(itpdThread);
            }
        } else {
            modulesStatus.setItpdState(STOPPED);
        }
    }

    private boolean stopITPDIfPortsIsBusy() {

        Set<String> itpdTunnelsPorts = new HashSet<>();

        List<String> lines = FileManager.readTextFileSynchronous(this, pathVars.get().getAppDataDir() + "/app_data/i2pd/tunnels.conf");
        for (String line : lines) {
            if (line.matches("^port ?= ?\\d+")) {
                String port = line.substring(line.indexOf("=") + 1).trim();
                if (port.matches("\\d+")) {
                    itpdTunnelsPorts.add(port);
                }
            }
        }

        preferenceRepository.get()
                .setStringSetPreference("ITPDTunnelsPorts", itpdTunnelsPorts);

        boolean stopRequired = false;

        for (String port : itpdTunnelsPorts) {
            if (isNotAvailable(port)) {
                stopRequired = true;
            }
        }

        stopRequired = stopRequired ||
                isNotAvailable(pathVars.get().getITPDSOCKSPort())
                || isNotAvailable(pathVars.get().getITPDHttpProxyPort());

        if (stopRequired) {
            try {
                modulesStatus.setItpdState(RESTARTING);

                Thread killerThread = new Thread(modulesKiller.getITPDKillerRunnable());
                killerThread.start();

                while (killerThread.isAlive()) {
                    killerThread.join();
                }

                makeDelay(5);

                if (modulesStatus.getItpdState() == RUNNING) {
                    return true;
                }

                modulesStatus.setItpdState(STARTING);

            } catch (InterruptedException e) {
                Log.e(LOG_TAG, "ModulesService restartITPD join interrupted!");
            }
        }
        return false;
    }

    private void stopDNSCrypt() {
        new Thread(modulesKiller.getDNSCryptKillerRunnable()).start();
    }

    private void stopTor() {
        new Thread(modulesKiller.getTorKillerRunnable()).start();
    }

    private void stopITPD() {
        new Thread(modulesKiller.getITPDKillerRunnable()).start();
    }

    private void restartDNSCrypt() {

        if (modulesStatus.getDnsCryptState() != RUNNING) {
            return;
        }


        new Thread(() -> {
            try {
                modulesStatus.setDnsCryptState(RESTARTING);

                Thread killerThread = new Thread(modulesKiller.getDNSCryptKillerRunnable());
                killerThread.start();

                while (killerThread.isAlive()) {
                    killerThread.join();
                }

                makeDelay(5);

                if (modulesStatus.getDnsCryptState() != RUNNING) {
                    startDNSCrypt();
                }

            } catch (InterruptedException e) {
                Log.e(LOG_TAG, "ModulesService restartDNSCrypt join interrupted!");
            }

        }).start();
    }

    private void restartTor() {

        if (modulesStatus.getTorState() != RUNNING) {
            return;
        }

        new Thread(() -> {
            try {
                modulesStatus.setTorState(RESTARTING);

                makeDelay(5);

                ModulesRestarter modulesRestarter = new ModulesRestarter();
                modulesRestarter.getTorRestarterRunnable(this).run();

                modulesStatus.setTorState(RUNNING);

                checkInternetConnection();

            } catch (Exception e) {
                Log.e(LOG_TAG, "ModulesService restartTor exception " + e.getMessage() + " " + e.getCause());
            }

        }).start();
    }

    private void restartTorFull() {
        if (modulesStatus.getTorState() != RUNNING) {
            return;
        }

        new Thread(() -> {
            try {
                modulesStatus.setTorState(RESTARTING);

                Thread killerThread = new Thread(modulesKiller.getTorKillerRunnable());
                killerThread.start();

                while (killerThread.isAlive()) {
                    killerThread.join();
                }

                makeDelay(5);

                if (modulesStatus.getTorState() != RUNNING) {
                    startTor();
                    checkInternetConnection();
                }

            } catch (InterruptedException e) {
                Log.e(LOG_TAG, "ModulesService restartTorFull join interrupted!");
            }

        }).start();
    }

    private void restartITPD() {

        if (modulesStatus.getItpdState() != RUNNING) {
            return;
        }

        new Thread(() -> {
            try {
                modulesStatus.setItpdState(RESTARTING);

                internetCheckerInteractor.get().setInternetConnectionResult(false);

                Thread killerThread = new Thread(modulesKiller.getITPDKillerRunnable());
                killerThread.start();

                while (killerThread.isAlive()) {
                    killerThread.join();
                }

                makeDelay(5);

                if (modulesStatus.getItpdState() != RUNNING) {
                    startITPD();
                }

            } catch (InterruptedException e) {
                Log.e(LOG_TAG, "ModulesService restartITPD join interrupted!");
            }

        }).start();
    }

    private void checkInternetConnection() {
        ConnectionCheckerInteractor interactor = internetCheckerInteractor.get();
        interactor.setInternetConnectionResult(false);
        interactor.checkInternetConnection();
    }

    private void dismissNotification(int startId) {
        try {
            systemNotificationManager.cancel(DEFAULT_NOTIFICATION_ID);
            stopForeground(true);
        } catch (Exception e) {
            Log.e(LOG_TAG, "ModulesService dismissNotification exception " + e.getMessage() + " " + e.getCause());
        }

        stopSelf(startId);
    }

    private void startModulesThreadsTimer() {
        checkModulesThreadsTimer = Executors.newSingleThreadScheduledExecutor();
        checkModulesStateTask = new ModulesStateLoop(this);
        scheduledFuture = checkModulesThreadsTimer.scheduleWithFixedDelay(checkModulesStateTask, 1, timerPeriod, TimeUnit.MILLISECONDS);
    }

    private void speedupTimer() {
        if (timerPeriod != TIMER_HIGH_SPEED && checkModulesThreadsTimer != null
                && !checkModulesThreadsTimer.isShutdown() && checkModulesStateTask != null) {

            timerPeriod = TIMER_HIGH_SPEED;

            if (scheduledFuture != null && !scheduledFuture.isCancelled()) {
                scheduledFuture.cancel(false);
            }

            scheduledFuture = checkModulesThreadsTimer.scheduleWithFixedDelay(checkModulesStateTask, 1, timerPeriod, TimeUnit.MILLISECONDS);

            Log.i(LOG_TAG, "ModulesService speedUPTimer");
        }
    }

    void slowdownTimer() {
        if (timerPeriod != TIMER_LOW_SPEED && checkModulesThreadsTimer != null
                && !checkModulesThreadsTimer.isShutdown() && checkModulesStateTask != null) {

            timerPeriod = TIMER_LOW_SPEED;

            if (scheduledFuture != null && !scheduledFuture.isCancelled()) {
                scheduledFuture.cancel(false);
            }

            scheduledFuture = checkModulesThreadsTimer.scheduleWithFixedDelay(checkModulesStateTask, 1, timerPeriod, TimeUnit.MILLISECONDS);

            Log.i(LOG_TAG, "ModulesService slowDOWNTimer");
        }
    }

    private void makeExtraLoop() {
        if (timerPeriod != TIMER_HIGH_SPEED && checkModulesStateTask != null) {
            cachedExecutor.submit(checkModulesStateTask);
        }
    }

    private void stopModulesThreadsTimer() {
        if (checkModulesThreadsTimer != null && !checkModulesThreadsTimer.isShutdown()) {
            checkModulesThreadsTimer.shutdown();
            checkModulesThreadsTimer = null;
        }
    }

    private void stopVPNServiceIfRunning() {
        OperationMode operationMode = modulesStatus.getMode();
        SharedPreferences prefs = android.preference.PreferenceManager.getDefaultSharedPreferences(this);
        if (((operationMode == VPN_MODE) || modulesStatus.isFixTTL()) && prefs.getBoolean(VPN_SERVICE_ENABLED, false)) {
            ServiceVPNHelper.stop("ModulesService is destroyed", this);
        }
    }

    private void manageWakelocks() {
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        boolean lock = sharedPreferences.getBoolean("swWakelock", false);

        wakeLocksManager = WakeLocksManager.getInstance();
        wakeLocksManager.managePowerWakelock(this, lock);
        wakeLocksManager.manageWiFiLock(this, lock);
    }

    private void releaseWakelocks() {
        if (wakeLocksManager != null) {
            wakeLocksManager.stopPowerWakelock();
            wakeLocksManager.stopWiFiLock();
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {

        unregisterModulesBroadcastReceiver();

        if (usageStatistic != null) {
            usageStatistic.stopUpdate();
        }

        releaseWakelocks();

        if (checkModulesStateTask != null && modulesStatus.getMode() == VPN_MODE) {
            checkModulesStateTask.removeHandlerTasks();
        }

        stopModulesThreadsTimer();

        stopArpScanner();

        stopVPNServiceIfRunning();

        if (handler != null) {
            handler.get().removeCallbacksAndMessages(null);
        }

        InternetSharingChecker.resetTetherInterfaceName();

        serviceIsRunning = false;

        super.onDestroy();
    }

    private void stopService(int startID) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                systemNotificationManager.cancel(DEFAULT_NOTIFICATION_ID);
                stopForeground(true);
            } catch (Exception e) {
                Log.e(LOG_TAG, "ModulesService stopService exception " + e.getMessage() + " " + e.getCause());
            }

        }

        stopSelf(startID);
    }

    private void setAllModulesStateStopped() {
        modulesStatus.setDnsCryptState(STOPPED);
        modulesStatus.setTorState(STOPPED);
        modulesStatus.setItpdState(STOPPED);
    }

    private void stopModulesService() {
        try {
            systemNotificationManager.cancel(DEFAULT_NOTIFICATION_ID);
            stopForeground(true);
        } catch (Exception e) {
            Log.e(LOG_TAG, "ModulesService stopModulesService exception " + e.getMessage() + " " + e.getCause());
        }

        stopSelf();
    }

    private void stopModulesServiceForeground() {

        try {
            systemNotificationManager.cancel(DEFAULT_NOTIFICATION_ID);
            stopForeground(true);
        } catch (Exception e) {
            Log.e(LOG_TAG, "ModulesService stopModulesServiceForeground1 exception " + e.getMessage() + " " + e.getCause());
        }
    }

    private void stopModulesServiceForeground(int startId) {

        try {
            systemNotificationManager.cancel(DEFAULT_NOTIFICATION_ID);
            stopForeground(true);
        } catch (Exception e) {
            Log.e(LOG_TAG, "ModulesService stopModulesServiceForeground2 exception " + e.getMessage() + " " + e.getCause());
        }

        stopSelf(startId);
    }

    private void setBroadcastReceiver() {
        if (modulesStatus.getMode() == ROOT_MODE
                && !modulesStatus.isUseModulesWithRoot()
                && modulesBroadcastReceiver == null) {
            modulesBroadcastReceiver = new ModulesBroadcastReceiver(this, arpScanner);
            modulesBroadcastReceiver.registerReceivers();
            internetCheckerInteractor.get().addListener(modulesBroadcastReceiver);
        } else if (modulesStatus.getMode() != ROOT_MODE
                && modulesBroadcastReceiver != null) {
            unregisterModulesBroadcastReceiver();
            internetCheckerInteractor.get().removeListener(modulesBroadcastReceiver);
            modulesBroadcastReceiver = null;
        }

    }

    private void unregisterModulesBroadcastReceiver() {
        if (modulesBroadcastReceiver != null) {
            try {
                modulesBroadcastReceiver.unregisterReceivers();
            } catch (Exception e) {
                Log.i(LOG_TAG, "ModulesService unregister receiver exception " + e.getMessage());
            }
        }
    }

    private void makeDelay(int sec) {
        try {
            TimeUnit.SECONDS.sleep(sec);
        } catch (InterruptedException e) {
            Log.e(LOG_TAG, "ModulesService makeDelay interrupted! " + e.getMessage() + " " + e.getCause());
        }
    }

    public Thread findThreadByName(String threadName) {
        Thread currentThread = Thread.currentThread();
        ThreadGroup threadGroup = getRootThreadGroup(currentThread);
        int allActiveThreads = threadGroup.activeCount();
        Thread[] allThreads = new Thread[allActiveThreads];
        threadGroup.enumerate(allThreads);

        for (Thread thread : allThreads) {
            String name = "";
            if (thread != null) {
                name = thread.getName();
            }
            //Log.i(LOG_TAG, "Current threads " + name);
            if (name.equals(threadName)) {
                Log.i(LOG_TAG, "Found old module thread " + name);
                return thread;
            }
        }

        return null;
    }

    private ThreadGroup getRootThreadGroup(Thread thread) {
        ThreadGroup rootGroup = thread.getThreadGroup();
        while (rootGroup != null) {
            ThreadGroup parentGroup = rootGroup.getParent();
            if (parentGroup == null) {
                break;
            }
            rootGroup = parentGroup;
        }
        return rootGroup;
    }

    private boolean isNotAvailable(String portStr) {

        int port = Integer.parseInt(portStr);

        ServerSocket ss = null;
        DatagramSocket ds = null;
        try {
            ss = new ServerSocket(port);
            ss.setReuseAddress(true);
            ds = new DatagramSocket(port);
            ds.setReuseAddress(true);
            return false;
        } catch (IOException ignored) {
        } finally {
            if (ds != null) {
                ds.close();
            }

            if (ss != null) {
                try {
                    ss.close();
                } catch (IOException ignored) {
                }
            }
        }

        return true;
    }

    private void cleanLogFileNoRootMethod(String logFilePath, String text) {
        try {
            File f = new File(pathVars.get().getAppDataDir() + "/logs");

            if (f.mkdirs() && f.setReadable(true) && f.setWritable(true))
                Log.i(LOG_TAG, "log dir created");

            PrintWriter writer = new PrintWriter(logFilePath, "UTF-8");
            writer.println(text);
            writer.close();
        } catch (IOException e) {
            Log.e(LOG_TAG, "Unable to create dnsCrypt log file " + e.getMessage());
        }
    }

    private void startArpScanner() {
        arpScanner = ArpScanner.INSTANCE.getInstance(this.getApplicationContext(), handler.get());
        arpScanner.start(this.getApplicationContext());
    }

    private void stopArpScanner() {
        if (arpScanner != null) {
            arpScanner.stop(this);
        }
    }

    private void clearIptablesCommandsSavedHash() {
        if (checkModulesStateTask != null) {
            checkModulesStateTask.clearIptablesCommandHash();
        }
    }

    private void checkModulesConfigPatches() {
        Patch patch = new Patch(this);
        patch.checkPatches(false);
    }
}
