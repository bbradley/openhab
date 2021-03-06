/**
 * openHAB, the open Home Automation Bus.
 * Copyright (C) 2010-2013, openHAB.org <admin@openhab.org>
 *
 * See the contributors.txt file in the distribution for a
 * full listing of individual contributors.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation; either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, see <http://www.gnu.org/licenses>.
 *
 * Additional permission under GNU GPL version 3 section 7
 *
 * If you modify this Program, or any covered work, by linking or
 * combining it with Eclipse (or a modified version of that library),
 * containing parts covered by the terms of the Eclipse Public License
 * (EPL), the licensors of this Program grant you additional permission
 * to convey the resulting work.
 */
package org.openhab.binding.nikobus.internal.config;

import org.openhab.binding.nikobus.internal.NikobusBinding;
import org.openhab.binding.nikobus.internal.core.NikobusCommand;
import org.openhab.binding.nikobus.internal.core.NikobusModule;
import org.openhab.binding.nikobus.internal.util.CRCUtil;
import org.openhab.binding.nikobus.internal.util.CommandCache;
import org.openhab.core.library.types.OnOffType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Group of 6 channels in a Nikobus switch module. This can be used to represent
 * either channels 0-4 for the compact switch module (05-002-02), or channels
 * 1-6 or 7-12 for the large switch module (05-000-02).
 * 
 * Example commands used by Nikobus for module with address 6C94. <br/>
 * <br/>
 * Request Status First Channel Group: <br/>
 * CMD : $10126C946CE5A0. <br/>
 * ACK : $0512 <br/>
 * REPLY: $1C6C9400000000FF0000557CF8.<br/>
 * 
 * <br/>
 * Request Status Second Channel Group: <br/>
 * CMD : $10176C948715BB. <br/>
 * ACK : $0517 <br/>
 * REPLY: $1C6C94000000FF0000FFCF4CC3. <br/>
 * <br/>
 * <br/>
 * Update Status First Channel Group: <br/>
 * CMD : $1E156C94000000FF0000FF60E149. <br/>
 * ACK : $0515 <br/>
 * REPLY: $0EFF6C94009A. <br/>
 * <br/>
 * Update Status Second Channel Group: <br/>
 * CMD : $1E166C940000000000FFFF997295. <br/>
 * ACK : $0516 <br/>
 * REPLY: $0EFF6C94009A.
 * 
 * @author Davy Vanherbergen
 * @since 1.3.0
 */
public class SwitchModuleChannelGroup implements NikobusModule {

	public static final String STATUS_REQUEST_CMD = "$10";
	public static final String STATUS_REQUEST_ACK = "$05";
	public static final String STATUS_REQUEST_GROUP_1 = "12";
	public static final String STATUS_REQUEST_GROUP_2 = "17";

	public static final String STATUS_RESPONSE = "$1C";

	public static final String STATUS_CHANGE_CMD = "$1E";
	public static final String STATUS_CHANGE_ACK = "$05";
	public static final String STATUS_CHANGE_GROUP_1 = "15";
	public static final String STATUS_CHANGE_GROUP_2 = "16";

	public static final String HIGH_BYTE = "FF";
	public static final String LOW_BYTE = "00";

	private Boolean nextStatusResponseIsForThisGroup;

	private String statusRequestGroup;
	private String statusUpdateGroup;

	private SwitchModuleChannel[] channels = new SwitchModuleChannel[6];

	private long lastUpdatedTime;
	private String address;
	private int group = 1;

	private static Logger log = LoggerFactory
			.getLogger(SwitchModuleChannelGroup.class);

	/**
	 * Default constructor.
	 * 
	 * @param address
	 *            Nikobus module address.
	 * @param group
	 *            1 or 2 indicating channels 1-6 or 7-12 respectively
	 * 
	 * @param bindingProvider
	 */
	public SwitchModuleChannelGroup(String address, int group) {
		this.address = address;
		this.group = group;

		if (group == 1) {
			statusRequestGroup = STATUS_REQUEST_GROUP_1;
			statusUpdateGroup = STATUS_CHANGE_GROUP_1;
		} else {
			statusRequestGroup = STATUS_REQUEST_GROUP_2;
			statusUpdateGroup = STATUS_CHANGE_GROUP_2;
		}
	}

	/**
	 * Add a new item channel to the switch module.
	 * 
	 * @param name
	 *            channel name
	 * @param channelNum
	 *            number 1-12
	 * @return SwitchModuleChannel bound to the given number.
	 */
	public SwitchModuleChannel addChannel(String name, int channelNum) {

		log.trace("Adding channel {}", name);

		if (channelNum > 6) {
			channelNum -= 6;
		}
		if (channelNum < 1 || channelNum > 6) {
			return null;
		}

		channels[channelNum - 1] = new SwitchModuleChannel(name, address, this);
		return channels[channelNum - 1];
	}

	/**
	 * Complete a command with the checksum from cache.
	 * 
	 * @param command
	 *            command to complete
	 */
	private NikobusCommand addChecksumToCommand(NikobusCommand command, NikobusBinding binding) {

		log.trace("Looking up checksum for command from cache {}",
				command.getCommand());

		CommandCache cache = binding.getCache();
		String checksum = cache.get(command.getCommand());
		if (checksum == null || checksum.length() == 0) {
			log.error(
					"Cannot find checksum value in cache for command {}. Please run analyzer first.",
					command);
			return null;
		}

		command.setCommand(command.getCommand() + checksum);
		return command;
	}

	/**
	 * Push the state of all channels to the Nikobus.
	 * 
	 * @param switchModuleChannel
	 */
	public void publishStateToNikobus(SwitchModuleChannel switchModuleChannel, NikobusBinding binding) {

		log.trace("Publishing group {}-{} status to eventbus and nikobus",
				address, group);

		// update the channel on the event bus..
		binding.postUpdate(switchModuleChannel.getName(),
				switchModuleChannel.getState());

		StringBuilder command = new StringBuilder();
		command.append(statusUpdateGroup);
		command.append(address);

		for (int i = 0; i < 6; i++) {
			if (channels[i] == null) {
				command.append(LOW_BYTE);
			} else if (channels[i].getState().equals(OnOffType.OFF)) {
				command.append(LOW_BYTE);
			} else {
				command.append(HIGH_BYTE);
			}
		}

		command.append(HIGH_BYTE);

		NikobusCommand cmd = addChecksumToCommand(new NikobusCommand(
				STATUS_CHANGE_CMD + CRCUtil.appendCRC(command.toString())), binding);

		try {
			binding.sendCommand(cmd);
		} catch (Exception e) {
			log.error("Error sending command.", e);
		}
	}

	/**
	 * {@inheritDoc}
	 * 
	 * The channel group can only process status update commands. These commands
	 * are sent by the switch module in response to a status request and contain
	 * the ON/OFF status of the different channels.
	 */
	@Override
	public void processNikobusCommand(NikobusCommand cmd, NikobusBinding binding) {

		String command = cmd.getCommand();

		// check if it was an ACK for a status request
		if (command.startsWith(STATUS_REQUEST_ACK)) {
			if (command.startsWith(STATUS_REQUEST_ACK + statusRequestGroup)) {
				nextStatusResponseIsForThisGroup = Boolean.TRUE;
			} else {
				nextStatusResponseIsForThisGroup = Boolean.FALSE;
			}
			return;
		}

		if (!command.startsWith(STATUS_RESPONSE)) {
			return;
		}

		if (!command.startsWith(STATUS_RESPONSE + address)
				|| nextStatusResponseIsForThisGroup == null
				|| nextStatusResponseIsForThisGroup.equals(Boolean.FALSE)) {
			nextStatusResponseIsForThisGroup = null;
			return;
		}

		log.debug(
				"Processing nikobus command {} for module ({}-{})",
				new Object[] { cmd.getCommand(), address,
						Integer.toString(group) });
		lastUpdatedTime = System.currentTimeMillis();

		// for every channel, update the status if it was changed
		for (int i = 0; i < 6; i++) {
			if (channels[i] == null) {
				continue;
			}
			if (command.substring(9 + (i * 2), 11 + (i * 2)).equals(LOW_BYTE)) {
				if (channels[i].getState().equals(OnOffType.ON)) {
					binding.postUpdate(channels[i].getName(), OnOffType.OFF);
					channels[i].setState(OnOffType.OFF);
				}
			} else {
				if (channels[i].getState().equals(OnOffType.OFF)) {
					binding.postUpdate(channels[i].getName(), OnOffType.ON);
					channels[i].setState(OnOffType.ON);
				}
			}
		}

	}

	/**
	 * @return time when last status feedback from the switch module was
	 *         received
	 */
	public long getLastUpdatedTime() {
		return lastUpdatedTime;
	}

	@Override
	public String getAddress() {
		return address;
	}

	@Override
	public NikobusCommand getStatusRequestCommand(NikobusBinding binding) {

		NikobusCommand cmd = new NikobusCommand(STATUS_REQUEST_CMD
				+ CRCUtil.appendCRC(statusRequestGroup + address),
				STATUS_RESPONSE + address, 2000);
		return addChecksumToCommand(cmd, binding);
	}

	@Override
	public String getName() {
		return address + "-" + group;
	}

}
