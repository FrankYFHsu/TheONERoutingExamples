/* 
 * Copyright 2018 Network Computing Laboratory, Department of Communication Engineering, 
 * National Central University.
 * Released under GPLv3. See LICENSE.txt for details. 
 */
package routing;

import java.util.HashMap;

import core.Settings;

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
	public void update() {
		super.update();
		if (isTransferring() || !canStartTransfer()) {
			return; // transferring, don't try other connections yet
		}

		// Try first the messages that can be delivered to final recipient
		if (exchangeDeliverableMessages() != null) {
			return; // started a transfer, don't try others (yet)
		}

		// TODO tryOtherMessages();
	}

	@Override
	public FRESHRouter replicate() {
		return new FRESHRouter(this);
	}

}