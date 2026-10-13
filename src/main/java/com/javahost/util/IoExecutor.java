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
		Thread thread = thread(task, "JavaHost IO ");
		thread.setPriority(Thread.NORM_PRIORITY - 1);
		return thread;
	});

	/** For network calls that mostly wait (router checks, update downloads, account lookups). */
	public static final ExecutorService NET = Executors.newCachedThreadPool(task -> thread(task, "JavaHost network "));

	private IoExecutor() {
	}

	/**
	 * A daemon thread that loads classes the way the game does. Without setting this, a thread started from
	 * somewhere outside the game's class loader can't find Minecraft's services (Java's ServiceLoader goes by the
	 * thread's loader): pinging a server from it then broke Minecraft's address checks for the whole session,
	 * so every join after that hung on "Connecting".
	 */
	private static Thread thread(Runnable task, String name) {
		Thread thread = new Thread(task, name + COUNT.incrementAndGet());
		thread.setDaemon(true);
		thread.setContextClassLoader(IoExecutor.class.getClassLoader());
		return thread;
	}

	public static <T> CompletableFuture<T> supply(Supplier<T> work) {
		return CompletableFuture.supplyAsync(work, POOL);
	}

	public static CompletableFuture<Void> run(Runnable work) {
		return CompletableFuture.runAsync(work, POOL);
	}
}
