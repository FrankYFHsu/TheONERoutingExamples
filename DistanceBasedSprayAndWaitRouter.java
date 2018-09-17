/* 
 * Copyright 2010 Aalto University, ComNet
 * Released under GPLv3. See LICENSE.txt for details. 
 */
package routing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import core.Connection;
import core.DTNHost;
import core.Message;
import core.Settings;
import util.Tuple;

/**
 * 
 *
 */
public class DistanceBasedSprayAndWaitRouter extends ActiveRouter {
	/** identifier for the initial number of copies setting ({@value}) */
	public static final String NROF_COPIES = "nrofCopies";
	/** identifier for the binary-mode setting ({@value}) */
	public static final String BINARY_MODE = "binaryMode";
	/** SprayAndWait router's settings name space ({@value}) */
	public static final String SPRAYANDWAIT_NS = "DistanceBasedSprayAndWaitRouter";
	/** Message property key */
	public static final String MSG_COUNT_PROPERTY = SPRAYANDWAIT_NS + "." + "copies";

	protected int initialNrofCopies;
	protected boolean isBinary;

	public DistanceBasedSprayAndWaitRouter(Settings s) {
		super(s);
		Settings snwSettings = new Settings(SPRAYANDWAIT_NS);

		initialNrofCopies = snwSettings.getInt(NROF_COPIES);
		isBinary = snwSettings.getBoolean(BINARY_MODE);
	}

	/**
	 * Copy constructor.
	 * 
	 * @param r The router prototype where setting values are copied from
	 */
	protected DistanceBasedSprayAndWaitRouter(DistanceBasedSprayAndWaitRouter r) {
		super(r);
		this.initialNrofCopies = r.initialNrofCopies;
		this.isBinary = r.isBinary;
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
	public void update() {
		super.update();
		if (!canStartTransfer() || isTransferring()) {
			return; // nothing to transfer or is currently transferring
		}

		/* try messages that could be delivered to final recipient */
		if (exchangeDeliverableMessages() != null) {
			return;
		}

		tryOtherMessages();

	}

	/**
	 * Tries to send all other messages to all connected hosts ordered by their
	 * distance with the destination
	 * 
	 * @return The return value of {@link #tryMessagesForConnected(List)}
	 */
	private Tuple<Message, Connection> tryOtherMessages() {

		List<MessageTupleForSortByDistance> messagesToBeReplicate = new ArrayList<MessageTupleForSortByDistance>();

		/* create a list of SAWMessages that have copies left to distribute */
		List<Message> msgCollection = getMessagesWithCopiesLeft();

		for (Connection con : getConnections()) {
			DTNHost other = con.getOtherNode(getHost());
			DistanceBasedSprayAndWaitRouter othRouter = (DistanceBasedSprayAndWaitRouter) other.getRouter();

			if (othRouter.isTransferring()) {
				continue; // skip hosts that are transferring
			}

			for (Message m : msgCollection) {
				if (othRouter.hasMessage(m.getId())) {
					continue; // skip messages that the other one has
				}

				DTNHost destiantion = m.getTo();

				double distanceBetweenMeAndDestination = getDistance(this.getHost(), destiantion);
				double distanceBetweenYouAndDestination = getDistance(othRouter.getHost(), destiantion);

				if (distanceBetweenYouAndDestination < distanceBetweenMeAndDestination) {

					MessageTupleForSortByDistance messageTuples = new MessageTupleForSortByDistance();
					messageTuples.setTuples(new Tuple<Message, Connection>(m, con));
					messageTuples.setDistance(distanceBetweenYouAndDestination);
					messagesToBeReplicate.add(messageTuples);
				}
			}

		}

		List<Tuple<Message, Connection>> messages = new ArrayList<Tuple<Message, Connection>>();

		if (messagesToBeReplicate.size() != 0) {

			Collections.sort(messagesToBeReplicate, new TupleComparatorForDistance());

			// extracts the tuple<message,connection> for the function:
			// tryMessagesForConnected
			for (int i = 0; i < messagesToBeReplicate.size(); i++) {
				MessageTupleForSortByDistance messageTuples = messagesToBeReplicate.get(i);
				messages.add(messageTuples.getTuples());
			}

			return tryMessagesForConnected(messages);
		} else {
			return null;
		}

	}

	private double getDistance(DTNHost host1, DTNHost host2) {

		return host1.getLocation().distance(host2.getLocation());

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
	public DistanceBasedSprayAndWaitRouter replicate() {
		return new DistanceBasedSprayAndWaitRouter(this);
	}
	
	private class MessageTupleForSortByDistance {

		private Tuple<Message, Connection> tuples;
		private double distance;

		public Tuple<Message, Connection> getTuples() {
			return tuples;
		}

		public void setTuples(Tuple<Message, Connection> tuples) {
			this.tuples = tuples;
		}

		public double getDistance() {
			return distance;
		}

		public void setDistance(double distance) {
			this.distance = distance;
		}

	}

	/**
	 * Comparator for MessageTupleForSortByDistance that orders the tuples by their
	 * distance to the destination.
	 */
	private class TupleComparatorForDistance implements Comparator<MessageTupleForSortByDistance> {

		@Override
		public int compare(MessageTupleForSortByDistance o1, MessageTupleForSortByDistance o2) {

			double p1 = o1.getDistance();
			double p2 = o2.getDistance();

			// shorter distance should come first
			if (p1 == p2) {
				/* equal distance -> let queue mode decide */
				return compareByQueueMode(o1.getTuples().getKey(), o2.getTuples().getKey());
			} else if (p1 > p2) {
				return 1;
			} else {
				return -1;
			}

		}

	}
}
