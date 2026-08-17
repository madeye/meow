package io.github.madeye.meow.aidl;
import io.github.madeye.meow.aidl.TrafficStats;

interface IMihomoServiceCallback {
    void stateChanged(int state, String profileName, String msg);
    void trafficUpdated(long profileId, in TrafficStats stats);
    void trafficPersisted(long profileId);
}
