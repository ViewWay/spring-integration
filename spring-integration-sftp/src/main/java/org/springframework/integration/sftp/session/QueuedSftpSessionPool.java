/*
 * Copyright 2002-2010 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.integration.sftp.session;

import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

import org.springframework.context.SmartLifecycle;
import org.springframework.util.Assert;

import com.jcraft.jsch.Channel;
import com.jcraft.jsch.Session;

/**
 * This approach - of having a SessionPool ({@link SftpSessionPool}) that has an
 * implementation of Queued*SessionPool ({@link QueuedSftpSessionPool}) - was
 * taken almost directly from the Spring Integration FTP adapter.
 *
 * @author Josh Long
 * @author Oleg Zhurakousky
 * @since 2.0
 */
public class QueuedSftpSessionPool implements SftpSessionPool, SmartLifecycle {
	
	private final ReentrantLock atomicOperationLock = new ReentrantLock();
	
	private static Logger logger = Logger.getLogger(QueuedSftpSessionPool.class.getName());

	public static final int DEFAULT_POOL_SIZE = 10;

	private volatile Queue<SftpSession> queue;

	private final SftpSessionFactory sftpSessionFactory;

	private final int maxPoolSize;
	
	private volatile boolean started;

	private volatile boolean autoStartup;

	public QueuedSftpSessionPool(SftpSessionFactory factory) {
		this(DEFAULT_POOL_SIZE, factory);
	}

	public QueuedSftpSessionPool(int maxPoolSize, SftpSessionFactory sessionFactory) {
		this.sftpSessionFactory = sessionFactory;
		this.maxPoolSize = maxPoolSize;
	}
	
	public void setAutoStartup(boolean autoStartup) {
		this.autoStartup = autoStartup;
	}

	public SftpSession getSession() throws Exception {
		Assert.notNull(this.queue, "SftpSession is unavailable since component is not started");
		this.atomicOperationLock.lock();
		try {
			SftpSession session = this.queue.poll();
			if (null == session) {
				session = this.sftpSessionFactory.getSession();
			}
			return session;
		} 
		finally {
			this.atomicOperationLock.unlock();
		}
		
	}

	public void release(SftpSession sftpSession) {
		if (this.started){
			this.atomicOperationLock.lock();
			try {
				if (queue.size() < maxPoolSize && sftpSession != null) {
					queue.add(sftpSession); 
				}
				else {
					this.destroySftpSession(sftpSession);
				}
			}
			finally {
				this.atomicOperationLock.unlock();
			}
		}
		else {
			this.destroySftpSession(sftpSession);
		}
	}
	
	public void start() {
		Assert.isTrue(this.maxPoolSize > 0, "poolSize must be greater than 0");
		this.atomicOperationLock.lock();
		try {
			this.queue = new ArrayBlockingQueue<SftpSession>(this.maxPoolSize, true); 
		}
		finally {
			this.atomicOperationLock.unlock();
		}
		this.started = true;
	}

	public void stop() {
		for (SftpSession sftpSession : queue) {
			this.destroySftpSession(sftpSession);
		}
	}

	public boolean isRunning() {
		return this.started;
	}
	public int getPhase() {
		return 0;
	}

	public boolean isAutoStartup() {
		return this.autoStartup;
	}

	public void stop(Runnable callback) {
		this.atomicOperationLock.lock();
		try {
			this.stop();
			callback.run();
		}
		finally {
			this.started = false;
			this.atomicOperationLock.unlock();
		}
	}

	private void destroySftpSession(SftpSession sftpSession){
		try {
			if (sftpSession != null){
				Channel channel = sftpSession.getChannel();
				if (channel.isConnected()){
					channel.disconnect();
				}
				Session session = sftpSession.getSession();
				if (session.isConnected()){
					session.disconnect();
				}
			}	
		} catch (Throwable e) {
			// log and ignore
			logger.warning("Exception was thrown during while destroying SftpSession. " + e);
		}
	}

	
}
