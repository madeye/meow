package io.github.madeye.meow.aidl;
import io.github.madeye.meow.aidl.IMeowServiceCallback;
import io.github.madeye.meow.aidl.TrafficStats;

interface IMeowService {
    int getState();
    String getProfileName();
    void registerCallback(in IMeowServiceCallback cb);
    void startListeningForBandwidth(in IMeowServiceCallback cb, long timeout);
    void stopListeningForBandwidth(in IMeowServiceCallback cb);
    void unregisterCallback(in IMeowServiceCallback cb);
}
