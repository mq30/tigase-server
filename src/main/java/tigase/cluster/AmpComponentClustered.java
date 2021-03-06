/*
 * Tigase XMPP Server - The instant messaging server
 * Copyright (C) 2004 Tigase, Inc. (office@tigase.com)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. Look for COPYING file in the top folder.
 * If not, see http://www.gnu.org/licenses/.
 */
package tigase.cluster;

import tigase.cluster.api.*;
import tigase.kernel.beans.Bean;
import tigase.kernel.beans.Inject;
import tigase.kernel.beans.selector.ClusterModeRequired;
import tigase.kernel.beans.selector.ConfigType;
import tigase.kernel.beans.selector.ConfigTypeEnum;
import tigase.kernel.core.Kernel;
import tigase.server.Message;
import tigase.server.Packet;
import tigase.server.Priority;
import tigase.server.amp.AmpComponent;
import tigase.util.stringprep.TigaseStringprepException;
import tigase.xml.Element;
import tigase.xmpp.jid.JID;

import java.util.*;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.logging.Level;
import java.util.logging.Logger;

import static tigase.server.amp.AmpFeatureIfc.FROM_CONN_ID;

/**
 * @author andrzej
 */
@Bean(name = "amp", parent = Kernel.class, active = true)
@ConfigType({ConfigTypeEnum.DefaultMode, ConfigTypeEnum.SessionManagerMode, ConfigTypeEnum.ConnectionManagersMode,
			 ConfigTypeEnum.ComponentMode})
@ClusterModeRequired(active = true)
public class AmpComponentClustered
		extends AmpComponent
		implements ClusteredComponentIfc {

	private static final Logger log = Logger.getLogger(AmpComponentClustered.class.getCanonicalName());

	@Inject
	private ClusterControllerIfc clusterController = null;
	private Set<CommandListener> commandListeners = new CopyOnWriteArraySet<CommandListener>();

	public AmpComponentClustered() {
		commandListeners.add(new PacketForwardCommand("packet-forward"));
	}

	@Override
	public void processPacket(Packet packet) {
		if (packet.getPacketFrom() == null || getComponentId().getDomain().equals(packet.getPacketFrom().getDomain())) {
			if (packet.getElemName() == Message.ELEM_NAME &&
					packet.getElement().getChild("broadcast", "http://tigase.org/protocol/broadcast") != null &&
					packet.getAttributeStaticStr(FROM_CONN_ID) == null) {
				forwardPacket(packet.copyElementOnly());
			}
		}
		super.processPacket(packet); //To change body of generated methods, choose Tools | Templates.
	}

	@Override
	public void setClusterController(ClusterControllerIfc cl_controller) {
		super.setClusterController(cl_controller);
		if (clusterController != null) {
			for (CommandListener listener : commandListeners) {
				clusterController.removeCommandListener(listener);
			}
		}
		clusterController = cl_controller;
		if (clusterController != null) {
			for (CommandListener listener : commandListeners) {
				clusterController.setCommandListener(listener);
			}
		}
	}

	protected void forwardPacket(Packet packet) {
		List<JID> toNodes = new ArrayList<JID>();
		for (JID jid : getNodesConnected()) {
			// jid of local node should not be part of getNodesConnected but let's keep this check for now
			if (jid.equals(getComponentId())) {
				continue;
			}
			toNodes.add(jid);
		}
		if (!toNodes.isEmpty()) {
			clusterController.sendToNodes("packet-forward", null, packet.getElement(), getComponentId(), null,
										  toNodes.toArray(new JID[toNodes.size()]));
		}
	}

	protected class PacketForwardCommand
			extends CommandListenerAbstract {

		public PacketForwardCommand(String name) {
			super(name, Priority.HIGH);
		}

		@Override
		public void executeCommand(JID fromNode, Set<JID> visitedNodes, Map<String, String> data,
								   Queue<Element> packets) throws ClusterCommandException {
			Element packetEl = null;
			while ((packetEl = packets.poll()) != null) {
				try {
					Packet packet = Packet.packetInstance(packetEl);
					packet.setPacketFrom(fromNode);
					packet.setPacketTo(getComponentId());
					AmpComponentClustered.this.addPacket(packet);
				} catch (TigaseStringprepException ex) {
					log.log(Level.WARNING, "exception converting element to packet after forwarding from other node",
							ex);
				}
			}
		}

	}
}
