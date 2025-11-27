package com.example.mediaservice;

import java.util.concurrent.*;
import java.util.concurrent.locks.*;
import java.util.*;

public class BoundedQueue<T> {
    private final List<T> queue;
    private final int capacity;
    private final ReentrantLock lock;
    private final Semaphore emptySlots;
    private final Semaphore filledSlots;
    private int droppedCount;

    public BoundedQueue(int capacity) {
        this.capacity = capacity;
        this.queue = new ArrayList<>(capacity);
        this.lock = new ReentrantLock();
        this.emptySlots = new Semaphore(capacity);
        this.filledSlots = new Semaphore(0);
        this.droppedCount = 0;
    }

    public boolean enqueue(T item) {
        // Try to acquire without blocking - leaky bucket behavior
        if (!emptySlots.tryAcquire()) {
            droppedCount++;
            return false;
        }
        
        lock.lock();
        try {
            queue.add(item);
            filledSlots.release();
            return true;
        } finally {
            lock.unlock();
        }
    }

    public T dequeue() throws InterruptedException {
        filledSlots.acquire();
        lock.lock();
        try {
            T item = queue.remove(0);
            emptySlots.release();
            return item;
        } finally {
            lock.unlock();
        }
    }

    public int size() {
        lock.lock();
        try {
            return queue.size();
        } finally {
            lock.unlock();
        }
    }

    public int getDroppedCount() {
        return droppedCount;
    }

    public int getCapacity() {
        return capacity;
    }
}