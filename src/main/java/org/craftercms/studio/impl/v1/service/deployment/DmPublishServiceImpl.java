/*
 * Copyright (C) 2007-2018 Crafter Software Corporation. All rights reserved.
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
package org.craftercms.studio.impl.v1.service.deployment;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.codec.digest.DigestUtils;
import org.craftercms.commons.validation.annotations.param.ValidateParams;
import org.craftercms.commons.validation.annotations.param.ValidateSecurePathParam;
import org.craftercms.commons.validation.annotations.param.ValidateStringParam;
import org.craftercms.studio.api.v1.constant.DmConstants;
import org.craftercms.studio.api.v1.exception.ServiceException;
import org.craftercms.studio.api.v1.log.Logger;
import org.craftercms.studio.api.v1.log.LoggerFactory;
import org.craftercms.studio.api.v1.repository.ContentRepository;
import org.craftercms.studio.api.v1.service.AbstractRegistrableService;
import org.craftercms.studio.api.v1.service.content.ContentService;
import org.craftercms.studio.api.v1.service.content.ObjectMetadataManager;
import org.craftercms.studio.api.v1.service.dependency.DependencyService;
import org.craftercms.studio.api.v1.service.deployment.DeploymentException;
import org.craftercms.studio.api.v1.service.deployment.DeploymentService;
import org.craftercms.studio.api.v1.service.deployment.DmPublishService;
import org.craftercms.studio.api.v1.service.objectstate.ObjectStateService;
import org.craftercms.studio.api.v1.service.security.SecurityService;
import org.craftercms.studio.api.v1.service.site.SiteService;
import org.craftercms.studio.api.v1.service.workflow.context.MultiChannelPublishingContext;
import org.craftercms.studio.api.v1.to.PublishingTargetTO;

import static org.craftercms.studio.api.v1.constant.StudioConstants.FILE_SEPARATOR;

public class DmPublishServiceImpl extends AbstractRegistrableService implements DmPublishService {

    private static final Logger logger = LoggerFactory.getLogger(DmPublishServiceImpl.class);

    protected DeploymentService deploymentService;
    protected SecurityService securityService;
    protected SiteService siteService;
    protected ContentService contentService;
    protected ContentRepository contentRepository;
    protected ObjectMetadataManager objectMetadataManager;
    protected ObjectStateService objectStateService;
    protected DependencyService dependencyService;

    @Override
    public void register() {
        this._servicesManager.registerService(DmPublishService.class, this);
    }

    @Override
    @ValidateParams
    public void publish(@ValidateStringParam(name = "site") final String site, List<String> paths,
                        ZonedDateTime launchDate, final MultiChannelPublishingContext mcpContext) {
        boolean scheduledDateIsNow = false;
        if (launchDate == null) {
            scheduledDateIsNow=true;
            launchDate = ZonedDateTime.now(ZoneOffset.UTC);
        }
        final String approver = securityService.getCurrentUser();
        final ZonedDateTime ld = launchDate;


        try {
            deploymentService.deploy(site, mcpContext.getPublishingChannelGroup(), paths, ld, approver,
                        mcpContext.getSubmissionComment(),scheduledDateIsNow );
        } catch (DeploymentException e) {
            logger.error("Error while submitting paths to publish");
        }

    }

    @Override
    @ValidateParams
    public void unpublish(@ValidateStringParam (name = "site") String site, List<String> paths, String approver) {
        unpublish(site, paths, approver, null);
    }

    @Override
    @ValidateParams
    public void unpublish(@ValidateStringParam(name = "site") String site, List<String> paths,
                          @ValidateStringParam(name = "approver") String approver, ZonedDateTime scheduleDate) {
        if (scheduleDate == null) {
            scheduleDate = ZonedDateTime.now(ZoneOffset.UTC);
        }
        try {
            deploymentService.delete(site, paths, approver, scheduleDate);
        } catch (DeploymentException ex) {
            logger.error("Unable to delete files due a error ",ex);
        }
    }

    /**
     * Checks if there are any publishing channels configure
     * @return true if there is at least one publishing channel config
     */
    @Override
    @ValidateParams
	public boolean hasChannelsConfigure(@ValidateStringParam(name = "site") String site,
                                        MultiChannelPublishingContext mcpContext) {
    	boolean toReturn = false;
        if (mcpContext != null) {
            List<PublishingTargetTO> publishingTargets = siteService.getPublishingTargetsForSite(site);
            for (PublishingTargetTO target : publishingTargets) {
                if (target.getDisplayLabel().equals(mcpContext.getPublishingChannelGroup())) {
                    return false;
                }
            }
        }
    	return toReturn;
    }

    @Override
    @ValidateParams
    public void bulkGoLive(@ValidateStringParam(name = "site") String site, @ValidateStringParam String environment,
                           @ValidateSecurePathParam(name = "path") String path) throws ServiceException {
        logger.info("Starting Bulk Go Live for path " + path + " site " + site);

        String queryPath = path;
        if (queryPath.startsWith(FILE_SEPARATOR + DmConstants.INDEX_FILE)) {
            queryPath = queryPath.replace(FILE_SEPARATOR + DmConstants.INDEX_FILE, "");
        }

        logger.debug("Get change set for subtree for site: " + site + " root path: " + queryPath);
        List<String> childrenPaths = new ArrayList<String>();

        childrenPaths = objectStateService.getChangeSetForSubtree(site, queryPath);

        logger.debug("Collected " + childrenPaths.size() + " content items for site " + site + " and root path "
                + queryPath);
        Set<String> processedPaths = new HashSet<String>();
        ZonedDateTime launchDate = ZonedDateTime.now(ZoneOffset.UTC);
        for (String childPath : childrenPaths) {
            String childHash = DigestUtils.md2Hex(childPath);
            logger.debug("Processing dependencies for site " + site + " path " + childPath);
            if (processedPaths.add(childHash)) {
                List<String> pathsToPublish = new ArrayList<String>();
                List<String> candidatesToPublish = new ArrayList<String>();
                pathsToPublish.add(childPath);
                candidatesToPublish.addAll(objectMetadataManager.getSameCommitItems(site, childPath));
                candidatesToPublish.addAll(dependencyService.getPublishingDependencies(site, childPath));
                for (String pathToAdd : candidatesToPublish) {
                    String hash = DigestUtils.md2Hex(pathToAdd);
                    if (processedPaths.add(hash)) {
                        pathsToPublish.add(pathToAdd);
                    }
                }
                String aprover = securityService.getCurrentUser();
                String comment = "Bulk Go Live invoked by " + aprover;
                logger.info("Deploying package of " + pathsToPublish.size() + " items for site " + site + " path "
                        + childPath);
                try {
                    deploymentService.deploy(site, environment, pathsToPublish, launchDate, aprover, comment, true);
                } catch (DeploymentException e) {
                    logger.error("Error while running bulk Go Live operation", e);
                } finally {
                    logger.debug("Finished processing deployment package for path " + childPath + " site " + site);
                }
            }
        }
        logger.info("Finished Bulk Go Live for path " + path + " site " + site);
    }

    public void setDeploymentService(DeploymentService deploymentService) {
        this.deploymentService = deploymentService;
    }

    public SecurityService getSecurityService() {
        return securityService;
    }

    public void setSecurityService(SecurityService securityService) {
        this.securityService = securityService;
    }

    public SiteService getSiteService() {
        return siteService;
    }

    public void setSiteService(SiteService siteService) {
        this.siteService = siteService;
    }

    public ContentService getContentService() {
        return contentService;
    }

    public void setContentService(ContentService contentService) {
        this.contentService = contentService;
    }

    public ContentRepository getContentRepository() {
        return contentRepository;
    }

    public void setContentRepository(ContentRepository contentRepository) {
        this.contentRepository = contentRepository;
    }

    public ObjectMetadataManager getObjectMetadataManager() {
        return objectMetadataManager;
    }

    public void setObjectMetadataManager(ObjectMetadataManager objectMetadataManager) {
        this.objectMetadataManager = objectMetadataManager;
    }

    public ObjectStateService getObjectStateService() {
        return objectStateService;
    }

    public void setObjectStateService(ObjectStateService objectStateService) {
        this.objectStateService = objectStateService;
    }

    public DependencyService getDependencyService() {
        return dependencyService;
    }

    public void setDependencyService(DependencyService dependencyService) {
        this.dependencyService = dependencyService;
    }
}
