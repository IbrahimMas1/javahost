package com.javahost.util;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Where JavaHost does slow disk work (zipping, hashing, decoding images, listing worlds): two background threads
 * of its own, so it never holds up the game or other mods sharing Java's common pool.
 */
public final class IoExecutor {
	private static final AtomicInteger COUNT = new AtomicInteger();
	public static final ExecutorService POOL = Executors.newFixedThreadPool(2, task -> {
		Thread thread = new Thread(task, "JavaHost IO " + COUNT.incrementAndGet());
		thread.setDaemon(true);
		thread.setPriority(Thread.NORM_PRIORITY - 1);
		return thread;
	});

	/** For network calls that mostly wait (router checks, update downloads, account lookups). */
	public static final ExecutorService NET = Executors.newCachedThreadPool(task -> {
		Thread thread = new Thread(task, "JavaHost network " + COUNT.incrementAndGet());
		thread.setDaemon(true);
		return thread;
	});

	private IoExecutor() {
	}

	public static <T> CompletableFuture<T> supply(Supplier<T> work) {
		return CompletableFuture.supplyAsync(work, POOL);
	}

	public static CompletableFuture<Void> run(Runnable work) {
		return CompletableFuture.runAsync(work, POOL);
	}
}
