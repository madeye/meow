package io.github.madeye.meow.aidl;
import io.github.madeye.meow.aidl.IMihomoServiceCallback;
import io.github.madeye.meow.aidl.TrafficStats;

interface IMihomoService {
    int getState();
    String getProfileName();
    void registerCallback(in IMihomoServiceCallback cb);
    void startListeningForBandwidth(in IMihomoServiceCallback cb, long timeout);
    void stopListeningForBandwidth(in IMihomoServiceCallback cb);
    void unregisterCallback(in IMihomoServiceCallback cb);
}
