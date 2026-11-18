package com.portfolio.worker;

import java.util.concurrent.CountDownLatch;

public final class WorkerApplication {
    private WorkerApplication() {}

    public static void main(String[] args) throws InterruptedException {
        CountDownLatch shutdown = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(shutdown::countDown));
        System.out.println("Worker placeholder started; queue consumption is not implemented yet.");
        shutdown.await();
    }
}
