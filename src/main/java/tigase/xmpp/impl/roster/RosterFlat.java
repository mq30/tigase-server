/*
 * Tigase Jabber/XMPP Server
 * Copyright (C) 2004-2007 "Artur Hefczyc" <artur.hefczyc@tigase.org>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. Look for COPYING file in the top folder.
 * If not, see http://www.gnu.org/licenses/.
 *
 * $Rev$
 * Last modified by $Author$
 * $Date$
 */
package tigase.xmpp.impl.roster;

import java.util.Queue;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.logging.Logger;
import tigase.xml.Element;
import tigase.xml.DomBuilderHandler;
import tigase.xml.SimpleParser;
import tigase.xml.SingletonFactory;
import tigase.xmpp.NotAuthorizedException;
import tigase.xmpp.XMPPResourceConnection;
import tigase.util.JIDUtils;
import tigase.db.TigaseDBException;

/**
 * Describe class RosterFlat here.
 *
 *
 * Created: Tue Feb 21 18:05:53 2006
 *
 * @author <a href="mailto:artur.hefczyc@tigase.org">Artur Hefczyc</a>
 * @version $Rev$
 */
public class RosterFlat extends RosterAbstract {

	/**
   * Private logger for class instancess.
   */
  private static final Logger log =
    Logger.getLogger("tigase.xmpp.impl.roster.RosterFlat");
	private static final SimpleParser parser = 
					SingletonFactory.getParserInstance();
	private static final int maxRosterSize =
					new Long(Runtime.getRuntime().maxMemory() / 250000L).intValue();

	private Map<String, RosterElement> loadUserRoster(XMPPResourceConnection session)
    throws NotAuthorizedException, TigaseDBException {
		Map<String, RosterElement> roster = new LinkedHashMap<String, RosterElement>();
		session.putCommonSessionData(ROSTER, roster);
		String roster_str = session.getData(null, ROSTER, null);
		log.finest("Loaded user roster: " + roster_str);
		if (roster_str != null && !roster_str.isEmpty()) {
			DomBuilderHandler domHandler = new DomBuilderHandler();
			parser.parse(domHandler, roster_str.toCharArray(), 0, roster_str.length());
			Queue<Element> elems = domHandler.getParsedElements();
			if (elems != null && elems.size() > 0) {
				for (Element elem: elems) {
					RosterElement relem = new RosterElement(elem);
					if (!addBuddy(relem, roster)) {
						break;
					}
				}
			}
		} else {
			// Try to load a roster from the 'old' style roster storage and
			// convert it the the flat roster storage
			Roster oldRoster = new Roster();
			String[] buddies = oldRoster.getBuddies(session);
			if (buddies != null && buddies.length > 0) {
				for (String buddy: buddies) {
					String name = oldRoster.getBuddyName(session, buddy);
					SubscriptionType subscr = oldRoster.getBuddySubscription(session, buddy);
					String[] groups = oldRoster.getBuddyGroups(session, buddy);
					RosterElement relem = new RosterElement(buddy, name, groups);
					relem.setSubscription(subscr);
					if (!addBuddy(relem, roster)) {
						break;
					}
				}
				saveUserRoster(session);
			}
		}
		return roster;
	}

	private boolean addBuddy(RosterElement relem,
					Map<String, RosterElement> roster) {
		if (roster.size() <  maxRosterSize) {
			roster.put(relem.getJid(), relem);
			return true;
		}
		return false;
	}

	private void saveUserRoster(XMPPResourceConnection session)
    throws NotAuthorizedException, TigaseDBException {
		Map<String, RosterElement> roster = getUserRoster(session);
		StringBuilder sb = new StringBuilder();
		for (RosterElement relem: roster.values()) {
			sb.append(relem.getRosterElement().toString());
		}
		log.finest("Saving user roster: " + sb.toString());
		session.setData(null, ROSTER, sb.toString());
	}

	@SuppressWarnings("unchecked")
	private Map<String, RosterElement> getUserRoster(XMPPResourceConnection session)
    throws NotAuthorizedException, TigaseDBException {
		Map<String, RosterElement> roster =
      (Map<String, RosterElement>)session.getCommonSessionData(ROSTER);
		if (roster == null) {
			roster = loadUserRoster(session);
		}
		return roster;
	}

	private RosterElement getRosterElement(XMPPResourceConnection session,
		String buddy)
    throws NotAuthorizedException, TigaseDBException {
		Map<String, RosterElement> roster = getUserRoster(session);
		return roster.get(JIDUtils.getNodeID(buddy));
	}

	@Override
	public String[] getBuddies(final XMPPResourceConnection session)
    throws NotAuthorizedException, TigaseDBException {
		Map<String, RosterElement> roster = getUserRoster(session);
    return roster.keySet().toArray(new String[0]);
  }

	@Override
  public String getBuddyName(final XMPPResourceConnection session,
		final String buddy)
    throws NotAuthorizedException, TigaseDBException {
		RosterElement relem = getRosterElement(session, buddy);
		if (relem == null) {
			return null;
		} else {
			return relem.getName();
		}
  }

	@Override
  public void setBuddyName(final XMPPResourceConnection session,
		final String buddy, final String name)
    throws NotAuthorizedException, TigaseDBException {
		RosterElement relem = getRosterElement(session, buddy);
		if (relem != null) {
			log.finest("Setting name: '"+name+"' for buddy: " + buddy);
			relem.setName(name);
			saveUserRoster(session);
		} else {
			log.warning("Setting buddy name for non-existen contact: " + buddy);
		}
  }

	@Override
  public void setBuddySubscription(final XMPPResourceConnection session,
    final SubscriptionType subscription, final String buddy)
		throws NotAuthorizedException, TigaseDBException {
		RosterElement relem = getRosterElement(session, buddy);
		if (relem != null) {
			relem.setSubscription(subscription);
			saveUserRoster(session);
		} else {
			log.warning("Missing roster contact for subscription set: " + buddy);
		}
  }

	@Override
  public SubscriptionType getBuddySubscription(
		final XMPPResourceConnection session,
    final String buddy) throws NotAuthorizedException, TigaseDBException {
		RosterElement relem = getRosterElement(session, buddy);
		if (relem == null) {
			return null;
		} else {
			return relem.getSubscription();
		}
		//		return SubscriptionType.both;
  }

	@Override
	public boolean removeBuddy(final XMPPResourceConnection session,
		final String jid) throws NotAuthorizedException, TigaseDBException {
		Map<String, RosterElement> roster = getUserRoster(session);
		roster.remove(jid);
		saveUserRoster(session);
		return true;
	}

	@Override
	public void addBuddy(XMPPResourceConnection session,
		String jid, String name, String[] groups)
    throws NotAuthorizedException, TigaseDBException {
		String buddy = JIDUtils.getNodeID(jid);
		RosterElement relem = getRosterElement(session, buddy);
		if (relem == null) {
			Map<String, RosterElement> roster = getUserRoster(session);
			relem = new RosterElement(buddy, name, groups);
			if (addBuddy(relem, roster)) {
				saveUserRoster(session);
			} else {
				throw new TigaseDBException("Too many elements in the user roster.");
			}
			log.finest("Added buddy to roster: " + jid);
		} else {
			relem.setName(name);
			relem.setGroups(groups);
			saveUserRoster(session);
			log.finest("Updated buddy in roster: " + jid);
		}
	}

	@Override
  public String[] getBuddyGroups(final XMPPResourceConnection session,
		final String buddy)
    throws NotAuthorizedException, TigaseDBException {
		RosterElement relem = getRosterElement(session, buddy);
		if (relem == null) {
			return null;
		} else {
			return relem.getGroups();
		}
  }

} // RosterFlat
