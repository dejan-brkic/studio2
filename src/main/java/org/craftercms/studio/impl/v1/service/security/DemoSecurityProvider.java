/*
 * Crafter Studio Web-content authoring solution
 * Copyright (C) 2007-2016 Crafter Software Corporation.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package org.craftercms.studio.impl.v1.service.security;

import org.apache.commons.lang3.StringUtils;
import org.craftercms.commons.http.RequestContext;
import org.craftercms.studio.api.v1.log.Logger;
import org.craftercms.studio.api.v1.log.LoggerFactory;
import org.craftercms.studio.api.v1.service.security.SecurityProvider;
import org.dom4j.Document;
import org.dom4j.DocumentException;
import org.dom4j.Element;
import org.dom4j.io.SAXReader;
import org.xml.sax.SAXException;

import java.io.File;
import java.util.*;

/**
 */
public class DemoSecurityProvider implements SecurityProvider {

    private final static Logger logger = LoggerFactory.getLogger(DemoSecurityProvider.class);

    private final static String DOCUMENT_USER_ROOT = "user";
    private final static String DOCUMENT_ELM_USERNAME = "username";
    private final static String DOCUMENT_ELM_PASSWORD = "password";
    private final static String DOCUMENT_ELM_EMAIL = "email";
    private final static String DOCUMENT_ELM_FIRSTNAME = "firstName";
    private final static String DOCUMENT_ELM_LASTNAME = "lastName";
    private final static String DOCUMENT_ELM_GROUPS = "groups";
    private final static String DOCUMENT_ELM_GROUP = "group";
    private final static String CONST_FAKETICKET = "_FAKETICKET";

    private final static String PROVIDER_TYPE = "file";
    private static final Logger LOGGER = LoggerFactory.getLogger(DemoSecurityProvider.class);
    public void init() {
        if (configuredProviderType.equals(PROVIDER_TYPE)) {
            logger.debug("Demo security provider is configured for use. Loading configuration from " + configLocation);
            loadConfiguration();
        }
    }

    protected void loadConfiguration() {
        Document document = null;
        userMap = new HashMap<String, User>();
        try {
            File file = new File(configLocation);
            SAXReader reader = new SAXReader();
            try {
                reader.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                reader.setFeature("http://xml.org/sax/features/external-general-entities", false);
                reader.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            }catch (SAXException ex){
                LOGGER.error("Unable to turn off external entity loading, This could be a security risk.", ex);
            }
            document = reader.read(file);
        } catch (DocumentException e) {
            e.printStackTrace();
        }
        if (document != null) {
            Element root = document.getRootElement();
            List<Element> users = root.selectNodes(DOCUMENT_USER_ROOT);
            for (Element userElm : users) {
                User user = new User();

                String username = userElm.valueOf(DOCUMENT_ELM_USERNAME);
                user.setUsername(username);

                String password = userElm.valueOf(DOCUMENT_ELM_PASSWORD);
                user.setPassword(password);

                String email = userElm.valueOf(DOCUMENT_ELM_EMAIL);
                user.setEmail(email);

                String firstName = userElm.valueOf(DOCUMENT_ELM_FIRSTNAME);
                user.setFirstName(firstName);

                String lastName = userElm.valueOf(DOCUMENT_ELM_LASTNAME);
                user.setLastName(lastName);

                List<Element> groupElms = userElm.selectNodes(DOCUMENT_ELM_GROUPS + "/" + DOCUMENT_ELM_GROUP);
                List<String> groupStrs = new ArrayList<>();
                for (Element groupElem : groupElms) {
                    groupStrs.add(groupElem.getTextTrim());
                }
                user.setGroups(groupStrs);

                userMap.put(user.getUsername(), user);
            }

            configLastUpdate = new Date();
        }
    }

    protected void checkIfUpdated() {
        File file = new File(configLocation);
        if (file != null && file.lastModified() > configLastUpdate.getTime()) {
            loadConfiguration();
        }
    }

    public Set<String> getUserGroups(String user) {
        checkIfUpdated();
        Set<String> toRet = new HashSet<String>();;
        User u = userMap.get(user);
        if (u != null) {
            for (String group : u.getGroups()) {
                toRet.add(group);
            }
        }
        return toRet;
    }

    private Map<String, String> activeUser = new HashMap<String, String>();
    Map<String, String> activeProcess = new HashMap<String, String>();

    public String getCurrentUser() {
        RequestContext context = RequestContext.getCurrent();
        String username = null;

        if(context!=null) {
            username = activeUser.get("username");
        }
        else {
             username = activeProcess.get("username"); 
        }

        return username;
    }

    public Map<String, String> getUserProfile(String user) {
        checkIfUpdated();
        Map<String, String> toRet = new HashMap<String, String>();;
        User u = userMap.get(user);
        if (u != null) {
            toRet.put("username", u.getUsername());
            toRet.put("email", u.getEmail());
            toRet.put("firstName", u.getFirstName());
            toRet.put("lastName", u.getLastName());
        }
        return toRet;
    }

    public boolean validateTicket(String ticket) {
        String theTicket = ticket;
        RequestContext context = RequestContext.getCurrent();
       
        if(theTicket == null) {
            if(context != null) {
                theTicket = activeUser.get("ticket");
            }
            else {
                theTicket = activeProcess.get("ticket");    
            }
        }

        return userMap.containsKey(theTicket.replace(CONST_FAKETICKET, ""));
    }

    public String authenticate(String username, String password) {
        RequestContext context = RequestContext.getCurrent();
        String ticket = null;
        User user = userMap.get(username);
        if (user != null) {
            if (StringUtils.equals(password, user.password)) {
                ticket = username + CONST_FAKETICKET;

                if (context != null) {
                    activeUser.put("username", username);
                    activeUser.put("ticket", ticket);

                } else {
                    activeProcess.put("username", username);
                    activeProcess.put("ticket", ticket);
                }
            }
        }

    	return ticket;
    }

    @Override
    public void addUserGroup(String groupName) {

    }

    @Override
    public void addUserGroup(String parentGroup, String groupName) {

    }

    @Override
    public void addUserToGroup(String groupName, String user) {

    }

    @Override
    public String getCurrentToken() {
        RequestContext context = RequestContext.getCurrent();
        if (context != null) {
            return activeUser.get("ticket");
        } else {
            return activeProcess.get("ticket");
        }
    }

    @Override
    public boolean logout() {
        activeUser.remove("username");
        activeUser.remove("ticket");
        return true;
    }

    @Override
    public void addContentWritePermission(String path, String group) {
        // do nothing
    }

    @Override
    public void addConfigWritePermission(String path, String group) {
        // do nothing
    }

    class User {

        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }

        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }

        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }

        public String getFirstName() { return firstName; }
        public void setFirstName(String firstName) { this.firstName = firstName; }

        public String getLastName() { return lastName; }
        public void setLastName(String lastName) { this.lastName = lastName; }

        public List<String> getGroups() { return groups; }
        public void setGroups(List<String> groups) { this.groups = groups; }

        protected String username;
        protected String password;
        protected String email;
        protected String firstName;
        protected String lastName;
        protected List<String> groups;
    }

    public String getConfigLocation() { return configLocation; }
    public void setConfigLocation(String configLocation) { this.configLocation = configLocation; }

    public String getConfiguredProviderType() { return configuredProviderType; }
    public void setConfiguredProviderType(String configuredProviderType) { this.configuredProviderType = configuredProviderType; }

    protected String configLocation;
    protected String configuredProviderType;

    protected Map<String, User> userMap;
    protected Date configLastUpdate;
}