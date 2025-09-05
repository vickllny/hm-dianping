package com.hmdp.utils;

import java.util.concurrent.TimeUnit;

public interface Sleeper {

    static void sleep(final TimeUnit timeUnit, final long time){
        try {
            timeUnit.sleep(time);
        }catch (InterruptedException e){}
    }

    static void sleepSeconds(final long seconds){
        sleep(TimeUnit.SECONDS, seconds);
    }

    static void sleepMillis(final long milliseconds){
        sleep(TimeUnit.MILLISECONDS, milliseconds);
    }

}
