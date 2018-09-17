/* 
 * Copyright 2018 Network Computing Laboratory, Department of Communication Engineering, 
 * National Central University.
 * Released under GPLv3. See LICENSE.txt for details. 
 */
package routing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;

import core.Connection;
import core.DTNHost;
import core.Message;
import core.Settings;
import core.SimClock;
import util.Tuple;

/**
 * FRESH: FResher Encounter SearcH Routing scheme
 * 
 * Paper: H. Dubois-Ferriere, M. Grossglauser, and M. Vetterli. Age matters:
 * efficient route discovery in mobile ad hoc networks using encounter ages. In
 * Proc. ACM MobiHoc’03, June 2003.
 */
public class FRESHRouter extends ActiveRouter {

	HashMap<Integer, Double> lastEncounterTime;

	/**
	 * Constructor. Creates a new message router based on the settings in the given
	 * Settings object.
	 * 
	 * @param s The settings object
	 */
	public FRESHRouter(Settings s) {
		super(s);
		// Nothing to do
	}

	/**
	 * Copy constructor.
	 * 
	 * @param r The router prototype where setting values are copied from
	 */
	protected FRESHRouter(FRESHRouter r) {
		super(r);
		lastEncounterTime = new HashMap<Integer, Double>();
	}

	@Override
	public void changedConnection(Connection con) {
		super.changedConnection(con);

		if (con.isUp()) {
			// Nothing to do.
		} else {
			// The encounter age is calculated from the time that two nodes' connection is
			// down in this example.
			double time = SimClock.getTime();
			DTNHost otherHost = con.getOtherNode(this.getHost());
			lastEncounterTime.put(otherHost.getAddress(), time);
		}

	}

	/**
	 * Get encounter age which is the time elapsed since the most recent encounter
	 * of this node and other node.
	 */
	public double getEncounterAge(int otherNodeID) {

		if (this.getHost().getAddress() == otherNodeID) {
			return 0.0;
		}

		if (this.lastEncounterTime.containsKey(otherNodeID)) {
			return SimClock.getTime() - lastEncounterTime.get(otherNodeID);
		} else {
			// Never encountered that other node.
			// In this case, the paper lets the encounter age be infinite, but let it be the
			// current simulation time has the same result.
			return SimClock.getTime();
		}
	}

	@Override
	public void update() {
		super.update();
		if (isTransferring() || !canStartTransfer()) {
			return; // transferring, don't try other connections yet
		}

		// Try first the messages that can be delivered to final recipient
		if (exchangeDeliverableMessages() != null) {
			return; // started a transfer, don't try others (yet)
		}

		tryOtherMessages();
	}
	
	/**
	 * Try to send all other messages to all connected hosts sorted by their
	 * encounter age with the destination in ascending order
	 * 
	 * @return The return value of {@link #tryMessagesForConnected(List)}
	 */
	private Tuple<Message, Connection> tryOtherMessages() {

		List<MessageTupleForSortByEncounterAge> messagesToBeReplicate = new ArrayList<MessageTupleForSortByEncounterAge>();

		/* Messages in the buffer */
		List<Message> msgCollection = getMessageCollection().stream().collect(Collectors.toList());

		for (Connection con : getConnections()) {
			DTNHost other = con.getOtherNode(getHost());
			FRESHRouter othRouter = (FRESHRouter) other.getRouter();

			if (othRouter.isTransferring()) {
				continue; // skip hosts that are transferring
			}

			for (Message m : msgCollection) {
				if (othRouter.hasMessage(m.getId())) {
					continue; // skip messages that the other one has
				}

				DTNHost destiantion = m.getTo();

				double encounterAgeOfThisNode = this.getEncounterAge(destiantion.getAddress());
				double encounterAgeOfOtherNode = othRouter.getEncounterAge(destiantion.getAddress());

				if (encounterAgeOfThisNode > encounterAgeOfOtherNode) {

					MessageTupleForSortByEncounterAge messageTuples = new MessageTupleForSortByEncounterAge();
					messageTuples.setTuples(new Tuple<Message, Connection>(m, con));
					messageTuples.setEncounterAge(encounterAgeOfOtherNode);
					messagesToBeReplicate.add(messageTuples);
				}
			}

		}

		List<Tuple<Message, Connection>> messages = new ArrayList<Tuple<Message, Connection>>();

		if (messagesToBeReplicate.size() != 0) {

			Collections.sort(messagesToBeReplicate, new TupleComparatorForEncounterAge());

			// extracts the tuple<message,connection> for the function:
			// tryMessagesForConnected
			for (int i = 0; i < messagesToBeReplicate.size(); i++) {
				MessageTupleForSortByEncounterAge messageTuples = messagesToBeReplicate.get(i);
				messages.add(messageTuples.getTuples());
			}

			return tryMessagesForConnected(messages);
		} else {
			return null;
		}

	}

	@Override
	public FRESHRouter replicate() {
		return new FRESHRouter(this);
	}

	/**
	 * The class storing Tuple<Message, Connection> with encounter age for
	 * transmission scheduling.
	 *
	 */
	private class MessageTupleForSortByEncounterAge {

		private Tuple<Message, Connection> tuples;
		private double encounterAge;

		public Tuple<Message, Connection> getTuples() {
			return tuples;
		}

		public void setTuples(Tuple<Message, Connection> tuples) {
			this.tuples = tuples;
		}

		public double getEncounterAge() {
			return encounterAge;
		}

		public void setEncounterAge(double encounterAge) {
			this.encounterAge = encounterAge;
		}

	}

	/**
	 * Comparator for MessageTupleForSortByEncounterAge that orders the tuples by
	 * their encounter age with the destination. The
	 * MessageTupleForSortByEncounterAge with the smaller encounter age will come
	 * first.
	 */
	private class TupleComparatorForEncounterAge implements Comparator<MessageTupleForSortByEncounterAge> {

		@Override
		public int compare(MessageTupleForSortByEncounterAge o1, MessageTupleForSortByEncounterAge o2) {

			double p1 = o1.getEncounterAge();
			double p2 = o2.getEncounterAge();

			// low encounter age should come first
			if (p1 == p2) {
				/* equal age -> let queue mode decide */
				return compareByQueueMode(o1.getTuples().getKey(), o2.getTuples().getKey());
			} else if (p1 > p2) {
				return 1; // o2 first, then o1
			} else {
				return -1;// o1 first, then o2
			}

		}

	}

}