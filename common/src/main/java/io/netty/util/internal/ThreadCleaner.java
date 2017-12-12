/*
 * Copyright 2017 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.util.internal;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Set;

/**
 * Allows a way to register some {@link Runnable} that will executed once there are no references to the {@link Thread}
 * anymore. This typically happens once the {@link Thread} dies / completes.
 */
public final class ThreadCleaner {

    // This will hold a reference to the ThreadCleanerReference which will be removed once we called cleanup()
    private static final Set<ThreadCleanerReference> LIVE_SET = new ConcurrentSet<ThreadCleanerReference>();
    private static final ReferenceQueue<Object> REFERENCE_QUEUE = new ReferenceQueue<Object>();

    static {
        AccessController.doPrivileged(new PrivilegedAction<Object>() {
            @Override
            public Object run() {
                Thread cleanupThread = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        for (;;) {
                            try {
                                ThreadCleanerReference reference =
                                        (ThreadCleanerReference) REFERENCE_QUEUE.remove();
                                try {
                                    reference.cleanup();
                                } finally {
                                    LIVE_SET.remove(reference);
                                }
                            } catch (InterruptedException ignore) {
                                // Just consume and move on.
                            }
                        }
                    }
                });
                cleanupThread.setPriority(Thread.MIN_PRIORITY);
                cleanupThread.setContextClassLoader(null);
                cleanupThread.setName("ThreadCleanerReaper");
                cleanupThread.setDaemon(true);
                cleanupThread.start();
                return null;
            }
        });
    }

    /**
     * Register the given {@link Thread} for which the {@link Runnable} will be executed once there are no references
     * to the object anymore, which typically happens once the {@link Thread} dies.
     *
     * This should only be used if there are no other ways to execute some cleanup once the {@link Thread} dies as
     * its not a cheap way to handle the cleanup.
     */
    public static void register(Thread thread, Runnable cleanupTask) {
        ThreadCleanerReference reference = new ThreadCleanerReference(thread,
                ObjectUtil.checkNotNull(cleanupTask, "cleanupTask"));
        LIVE_SET.add(reference);
    }

    private ThreadCleaner() {
        // Only contains a  static method.
    }

    private static final class ThreadCleanerReference extends WeakReference<Thread> {
        private final Runnable cleanupTask;

        ThreadCleanerReference(Thread referent, Runnable cleanupTask) {
            super(referent, REFERENCE_QUEUE);
            this.cleanupTask = cleanupTask;
        }

        void cleanup() {
            cleanupTask.run();
        }

        @Override
        public Thread get() {
            return null;
        }

        @Override
        public void clear() {
            LIVE_SET.remove(this);
            super.clear();
        }
    }
}
