package io.astrbot.android.debug;

interface IProcessLimitService {
    String inspect() = 0;
    String setLimit(boolean restoreDefault) = 1;
    String setBatteryExemption(boolean enabled) = 2;
    String deviceOperation(String operation, String arguments) = 3;
    void destroy() = 16777114;
}
