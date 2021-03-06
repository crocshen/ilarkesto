/*
 * Copyright 2011 Witoslaw Koczewsi <wi@koczewski.de>, Artjom Kochtchi
 * 
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero
 * General Public License as published by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the
 * implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 * 
 * You should have received a copy of the GNU General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */
package ilarkesto.gwt.server;

import ilarkesto.base.PermissionDeniedException;
import ilarkesto.base.Sys;
import ilarkesto.base.Utl;
import ilarkesto.core.logging.Log;
import ilarkesto.core.persistance.TransferableEntity;
import ilarkesto.core.time.DateAndTime;
import ilarkesto.core.time.TimePeriod;
import ilarkesto.gwt.client.ADataTransferObject;
import ilarkesto.persistence.TransactionService;
import ilarkesto.webapp.AWebSession;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public abstract class AGwtConversation<S extends AWebSession, E extends TransferableEntity> implements
		Comparable<AGwtConversation> {

	private static final Log LOG = Log.get(AGwtConversation.class);
	private static final TimePeriod DEFAULT_TIMEOUT = TimePeriod.minutes(2);

	private TransactionService transactionService;

	/**
	 * Data that will be transferred to the client at the next request.
	 */
	private ADataTransferObject nextData;
	private Object nextDataLock = new Object();
	private Map<E, DateAndTime> remoteEntityModificationTimes = new HashMap<E, DateAndTime>();

	private S session;
	private int number;
	private DateAndTime lastTouched;

	protected abstract ADataTransferObject createDataTransferObject();

	public AGwtConversation(S session, int number) {
		super();
		this.session = session;
		this.number = number;

		nextData = createDataTransferObject();
		if (nextData != null) {
			nextData.developmentMode = Sys.isDevelopmentMode();
			nextData.entityIdBase = UUID.randomUUID().toString();
			nextData.conversationNumber = number;
		}

		touch();
	}

	public int getNumber() {
		return number;
	}

	public final void clearRemoteEntities() {
		remoteEntityModificationTimes.clear();
	}

	public final void clearRemoteEntitiesByType(Class<? extends E> type) {
		List<E> toRemove = new ArrayList<E>();
		for (E entity : remoteEntityModificationTimes.keySet()) {
			if (entity.getClass().equals(type)) toRemove.add(entity);
		}
		for (E entity : toRemove) {
			remoteEntityModificationTimes.remove(entity);
		}
	}

	protected boolean isEntityVisible(E entity) {
		return true;
	}

	protected void filterEntityProperties(E entity, Map propertiesMap) {}

	public synchronized boolean isAvailableOnClient(E entity) {
		return remoteEntityModificationTimes.containsKey(entity);
	}

	public synchronized void sendToClient(E entity) {
		if (entity == null) return;

		if (transactionService != null && !transactionService.isPersistent(entity.getId())) {
			getNextData().addDeletedEntity(entity.getId());
			return;
		}

		if (!isEntityVisible(entity)) throw new PermissionDeniedException(entity + " is not visible");

		sendToClient((Set<E>) entity.getSlaves());

		DateAndTime timeRemote = remoteEntityModificationTimes.get(entity);
		DateAndTime timeLocal = entity.getLastModified();

		if (timeLocal.equals(timeRemote)) {
			LOG.debug("Remote entity already up to date:", Utl.toStringWithType(entity), "for", this);
			return;
		}

		Map propertiesMap = entity.createPropertiesMap();
		filterEntityProperties(entity, propertiesMap);

		getNextData().addEntity(propertiesMap);
		remoteEntityModificationTimes.put(entity, timeLocal);
		LOG.debug("Sending", Utl.toStringWithType(entity), "to", this);
	}

	public final void sendToClient(E... entities) {
		if (entities == null) return;
		for (E entity : entities) {
			sendToClient(entity);
		}
	}

	public final void sendToClient(Collection<? extends E> entities) {
		if (entities == null) return;
		for (E entity : entities) {
			sendToClient(entity);
		}
	}

	public final ADataTransferObject popNextData() {
		if (nextData == null) return null;
		synchronized (nextDataLock) {
			ADataTransferObject ret = nextData;
			nextData = createDataTransferObject();
			return ret;
		}
	}

	public ADataTransferObject getNextData() {
		return nextData;
	}

	public S getSession() {
		return session;
	}

	public final void touch() {
		lastTouched = DateAndTime.now();
	}

	protected TimePeriod getTimeout() {
		return DEFAULT_TIMEOUT;
	}

	public final boolean isTimeouted() {
		return lastTouched.getPeriodToNow().isGreaterThen(getTimeout());
	}

	public final DateAndTime getLastTouched() {
		return lastTouched;
	}

	public void setTransactionService(TransactionService transactionService) {
		this.transactionService = transactionService;
	}

	public void invalidate() {}

	@Override
	public String toString() {
		return "#" + number + "@" + getSession();
	}

	@Override
	public int compareTo(AGwtConversation o) {
		return Utl.compare(o.getLastTouched(), getLastTouched());
	}
}
