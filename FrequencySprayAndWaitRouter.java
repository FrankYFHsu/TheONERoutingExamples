/* 
 * Copyright 2018 Network Computing Laboratory, Department of Communication Engineering, 
 * National Central University.
 * Released under GPLv3. See LICENSE.txt for details. 
 */
package routing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;

import core.Connection;
import core.DTNHost;
import core.Message;
import core.Settings;
import core.SimClock;
import util.Tuple;

public class FrequencySprayAndWaitRouter extends ActiveRouter {
	/** identifier for the initial number of copies setting ({@value}) */
	public static final String NROF_COPIES = "nrofCopies";
	/** identifier for the binary-mode setting ({@value}) */
	public static final String BINARY_MODE = "binaryMode";
	/** identifier for the time window size ({@value} ) */
	public static final String TIME_WINDOW = "timeWindow";
	/** SprayAndWait router's settings name space ({@value}) */
	public static final String SPRAYANDWAIT_NS = "FrequencySprayAndWaitRouter";
	/** Message property key */
	public static final String MSG_COUNT_PROPERTY = SPRAYANDWAIT_NS + "." + "copies";

	protected int initialNrofCopies;
	protected boolean isBinary;
	private double timeWindow;
	private FrequencyRecord frequencyRecord;

	public FrequencySprayAndWaitRouter(Settings s) {
		super(s);
		Settings snwSettings = new Settings(SPRAYANDWAIT_NS);

		initialNrofCopies = snwSettings.getInt(NROF_COPIES);
		isBinary = snwSettings.getBoolean(BINARY_MODE);
		timeWindow = snwSettings.getDouble(TIME_WINDOW);
	}

	/**
	 * Copy constructor.
	 * 
	 * @param r The router prototype where setting values are copied from
	 */
	protected FrequencySprayAndWaitRouter(FrequencySprayAndWaitRouter r) {
		super(r);
		this.initialNrofCopies = r.initialNrofCopies;
		this.isBinary = r.isBinary;
		this.timeWindow = r.timeWindow;

		frequencyRecord = new FrequencyRecord(timeWindow);
	}

	@Override
	public int receiveMessage(Message m, DTNHost from) {
		return super.receiveMessage(m, from);
	}

	@Override
	public Message messageTransferred(String id, DTNHost from) {
		Message msg = super.messageTransferred(id, from);
		Integer nrofCopies = (Integer) msg.getProperty(MSG_COUNT_PROPERTY);

		assert nrofCopies != null : "Not a SnW message: " + msg;

		if (isBinary) {
			/* in binary S'n'W the receiving node gets floor(n/2) copies */
			nrofCopies = (int) Math.floor(nrofCopies / 2.0);
		} else {
			/* in standard S'n'W the receiving node gets only single copy */
			nrofCopies = 1;
		}

		msg.updateProperty(MSG_COUNT_PROPERTY, nrofCopies);
		return msg;
	}

	@Override
	public boolean createNewMessage(Message msg) {
		makeRoomForNewMessage(msg.getSize());

		msg.setTtl(this.msgTtl);
		msg.addProperty(MSG_COUNT_PROPERTY, new Integer(initialNrofCopies));
		addToMessages(msg, true);
		return true;
	}

	@Override
	public void changedConnection(Connection con) {
		super.changedConnection(con);

		if (con.isUp()) {
			DTNHost otherHost = con.getOtherNode(this.getHost());
			frequencyRecord.addOneRecord(otherHost.getAddress(), SimClock.getTime());
		}

	}

	@Override
	public void update() {
		super.update();
		if (!canStartTransfer() || isTransferring()) {
			return; // nothing to transfer or is currently transferring
		}

		/* try messages that could be delivered to final recipient */
		if (exchangeDeliverableMessages() != null) {
			return;
		}

		/* create a list of SAWMessages that have copies left to distribute */
		@SuppressWarnings(value = "unchecked")
		List<Message> copiesLeft = sortByQueueMode(getMessagesWithCopiesLeft());

		if (copiesLeft.size() > 0) {
			/* try to send those messages */
			this.tryMessagesToConnections(copiesLeft, getConnections());
		}
	}

	/**
	 * Creates and returns a list of messages this router is currently carrying and
	 * still has copies left to distribute (nrof copies > 1).
	 * 
	 * @return A list of messages that have copies left
	 */
	protected List<Message> getMessagesWithCopiesLeft() {
		List<Message> list = new ArrayList<Message>();

		for (Message m : getMessageCollection()) {
			Integer nrofCopies = (Integer) m.getProperty(MSG_COUNT_PROPERTY);
			assert nrofCopies != null : "SnW message " + m + " didn't have " + "nrof copies property!";
			if (nrofCopies > 1) {
				list.add(m);
			}
		}

		return list;
	}

	/**
	 * Called just before a transfer is finalized (by
	 * {@link ActiveRouter#update()}). Reduces the number of copies we have left for
	 * a message. In binary Spray and Wait, sending host is left with floor(n/2)
	 * copies, but in standard mode, nrof copies left is reduced by one.
	 */
	@Override
	protected void transferDone(Connection con) {
		Integer nrofCopies;
		String msgId = con.getMessage().getId();
		/* get this router's copy of the message */
		Message msg = getMessage(msgId);

		if (msg == null) { // message has been dropped from the buffer after..
			return; // ..start of transfer -> no need to reduce amount of copies
		}

		/* reduce the amount of copies left */
		nrofCopies = (Integer) msg.getProperty(MSG_COUNT_PROPERTY);
		if (isBinary) {
			/* in binary S'n'W the sending node keeps ceil(n/2) copies */
			nrofCopies = (int) Math.ceil(nrofCopies / 2.0);
		} else {
			nrofCopies--;
		}
		msg.updateProperty(MSG_COUNT_PROPERTY, nrofCopies);
	}

	@Override
	public FrequencySprayAndWaitRouter replicate() {
		return new FrequencySprayAndWaitRouter(this);
	}

	private class MessageTupleForSortByFrequency {

		private Tuple<Message, Connection> tuples;
		private double frequency;

		public Tuple<Message, Connection> getTuples() {
			return tuples;
		}

		public void setTuples(Tuple<Message, Connection> tuples) {
			this.tuples = tuples;
		}

		public double getFrequency() {
			return frequency;
		}

		public void setFrequency(double distance) {
			this.frequency = distance;
		}

	}

	/**
	 * Comparator for MessageTupleForSortByFrequency that sorts the tuples by their
	 * contact frequency during past (TimeWindow) time to the destination.
	 */
	private class TupleComparatorForFrequency implements Comparator<MessageTupleForSortByFrequency> {

		@Override
		public int compare(MessageTupleForSortByFrequency o1, MessageTupleForSortByFrequency o2) {

			double p1 = o1.getFrequency();
			double p2 = o2.getFrequency();

			// higher frequency should come first
			if (p1 == p2) {
				/* equal frequency -> let queue mode decide */
				return compareByQueueMode(o1.getTuples().getKey(), o2.getTuples().getKey());
			} else if (p1 < p2) {
				return 1;
			} else {
				return -1;
			}

		}

	}

	/**
	 * The class to record contacts with other nodes.
	 *
	 */
	private class FrequencyRecord {

		private HashMap<Integer, LinkedList<Double>> contactTimeInformationTable;
		private double timeWindow;

		public FrequencyRecord(double timeWindow) {
			contactTimeInformationTable = new HashMap<Integer, LinkedList<Double>>();
			this.timeWindow = timeWindow;
		}

		public boolean addOneRecord(int hostAddress, double occurTime) {

			boolean result;
			LinkedList<Double> contactTimeSequence;
			if (contactTimeInformationTable.containsKey(hostAddress)) {
				contactTimeSequence = contactTimeInformationTable.get(hostAddress);

			} else {
				contactTimeSequence = new LinkedList<Double>();

			}

			result = contactTimeSequence.add(occurTime);
			if (result) {
				contactTimeSequence = checkVaildRecord(contactTimeSequence, occurTime);
				contactTimeInformationTable.put(hostAddress, contactTimeSequence);
			} else {
				System.err.println("false in addOneRecord");// May not do this
			}

			return result;

		}

		private LinkedList<Double> checkVaildRecord(LinkedList<Double> contactTimeSequence, double currentTime) {

			while (contactTimeSequence.peek() != null && (currentTime - contactTimeSequence.peek() > timeWindow)) {
				contactTimeSequence.poll();
			}

			return contactTimeSequence;

		}

		public double getFrequency(int hostAddress, double currentTime) {

			LinkedList<Double> contactTimeSequence;
			if (contactTimeInformationTable.containsKey(hostAddress)) {
				contactTimeSequence = contactTimeInformationTable.get(hostAddress);
				contactTimeSequence = checkVaildRecord(contactTimeSequence, currentTime);

				return (contactTimeSequence.size()) / timeWindow;

			} else {
				return 0.0;

			}

		}

	}
}
