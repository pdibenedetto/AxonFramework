/*
 * Copyright (c) 2010-2012. Axon Framework
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.axonframework.eventhandling.scheduling.java;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Implementation of ScheduledExecutorService that delegates to another ScheduledExecutorService, while keeping track
 * of all the futures of tasks that have been submitted to it.
 *
 * @author Allard Buijze
 */
public class ResultStoringScheduledExecutorService implements ScheduledExecutorService {

    private final ScheduledExecutorService delegate;
    private final List<Future<?>> results = new CopyOnWriteArrayList<Future<?>>();

    public ResultStoringScheduledExecutorService(ScheduledExecutorService delegate) {
        this.delegate = delegate;
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        ScheduledFuture<?> future = delegate.schedule(command, delay, unit);
        results.add(future);
        return future;
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
        ScheduledFuture<V> future = delegate.schedule(callable, delay, unit);
        results.add(future);
        return future;
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
        return delegate.scheduleAtFixedRate(command, initialDelay, period, unit);
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
        ScheduledFuture<?> future = delegate.scheduleWithFixedDelay(command, initialDelay, delay, unit);
        results.add(future);
        return future;
    }

    @Override
    public void shutdown() {
        delegate.shutdown();
    }

    @Override
    public List<Runnable> shutdownNow() {
        return delegate.shutdownNow();
    }

    @Override
    public boolean isShutdown() {
        return delegate.isShutdown();
    }

    @Override
    public boolean isTerminated() {
        return delegate.isTerminated();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return delegate.awaitTermination(timeout, unit);
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
        Future<T> future = delegate.submit(task);
        results.add(future);
        return future;
    }

    @Override
    public <T> Future<T> submit(Runnable task, T result) {
        Future<T> future = delegate.submit(task, result);
        results.add(future);
        return future;
    }

    @Override
    public Future<?> submit(Runnable task) {
        Future<?> future = delegate.submit(task);
        results.add(future);
        return future;
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException {
        List<Future<T>> futures = delegate.invokeAll(tasks);
        results.addAll(futures);
        return futures;
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException {
        List<Future<T>> futures = delegate.invokeAll(tasks, timeout, unit);
        results.addAll(futures);
        return futures;
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException {
        throw new UnsupportedOperationException("This operation is not supported");
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        throw new UnsupportedOperationException("This operation is not supported");
    }

    @Override
    public void execute(Runnable command) {
        throw new UnsupportedOperationException("This operation is not supported");
    }

    public List<Future<?>> getResults() {
        return results;
    }
}
