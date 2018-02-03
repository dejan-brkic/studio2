package org.craftercms.studio.impl.v1.service.dependency;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.craftercms.commons.validation.annotations.param.ValidateSecurePathParam;
import org.craftercms.commons.validation.annotations.param.ValidateStringParam;
import org.craftercms.studio.api.v1.constant.DmConstants;
import org.craftercms.studio.api.v1.dal.DependencyEntity;
import org.craftercms.studio.api.v1.dal.DependencyMapper;
import org.craftercms.studio.api.v1.exception.ContentNotFoundException;
import org.craftercms.studio.api.v1.exception.ServiceException;
import org.craftercms.studio.api.v1.exception.SiteNotFoundException;
import org.craftercms.studio.api.v1.log.Logger;
import org.craftercms.studio.api.v1.log.LoggerFactory;
import org.craftercms.studio.api.v1.service.content.ContentService;
import org.craftercms.studio.api.v1.service.content.ObjectMetadataManager;
import org.craftercms.studio.api.v1.service.dependency.DependencyResolver;
import org.craftercms.studio.api.v1.service.dependency.DependencyService;
import org.craftercms.studio.api.v1.service.objectstate.State;
import org.craftercms.studio.api.v1.service.site.SiteService;
import org.craftercms.studio.api.v1.to.ContentItemTO;
import org.craftercms.studio.impl.v1.util.ContentUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DependencyServiceImpl implements DependencyService {

    private static final Logger logger = LoggerFactory.getLogger(DependencyServiceImpl.class);

    @Autowired
    protected DependencyMapper dependencyMapper;

    protected SiteService siteService;
    protected ContentService contentService;
    protected DependencyResolver dependencyResolver;
    protected PlatformTransactionManager transactionManager;
    protected List<String> itemSpecificDependencies;
    protected ObjectMetadataManager objectMetadataManager;


    @Override
    public Set<String> upsertDependencies(String site, String path) throws SiteNotFoundException, ContentNotFoundException, ServiceException {
        Set<String> toRet = new HashSet<String>();
        logger.debug("Resolving dependencies for content site: " + site + " path: " + path);
        Map<String, Set<String>> dependencies = dependencyResolver.resolve(site, path);
        List<DependencyEntity> dependencyEntities = new ArrayList<>();
        if (dependencies != null) {
            logger.debug("Found " + dependencies.size() + " dependencies. Create entities to insert into database.");
            for (String type : dependencies.keySet()) {
                dependencyEntities.addAll(createDependencyEntities(site, path, dependencies.get(type), type, toRet));
            }

            logger.debug("Preparing transaction for database updates.");
            DefaultTransactionDefinition defaultTransactionDefinition = new DefaultTransactionDefinition();
            defaultTransactionDefinition.setName("upsertDependencies");

            logger.debug("Starting transaction.");
            TransactionStatus txStatus = transactionManager.getTransaction(defaultTransactionDefinition);

            try {
                logger.debug("Delete all source dependencies for site: " + site + " path: " + path);
                deleteAllSourceDependencies(site, path);
                logger.debug("Insert all extracted dependencies entries for site: " + site + " path: " + path);
                insertDependencies(dependencyEntities);
                logger.debug("Committing transaction.");
                transactionManager.commit(txStatus);
            } catch (Exception e) {
                logger.debug("Rolling back transaction.");
                transactionManager.rollback(txStatus);
                throw new ServiceException("Failed to upsert dependencies for site: " + site + " path: " + path, e);
            }

        }
        return toRet;
    }

    private void deleteAllSourceDependencies(String site, String path) {
        logger.debug("Delete all source dependencies for site: " + site + " path: " + path);
        Map<String, String> params = new HashMap<String, String>();
        params.put("site", site);
        params.put("path", path);
        dependencyMapper.deleteAllSourceDependencies(params);
    }

    private List<DependencyEntity> createDependencyEntities(String site, String path, Set<String> dependencyPaths, String dependencyType, Set<String> extractedPaths) {
        logger.debug("Create dependency entity TO for site: " + site + " path: " + path);
        List<DependencyEntity> dependencyEntities = new ArrayList<>();
        if (dependencyPaths != null && dependencyPaths.size() > 0) {
            for (String file : dependencyPaths) {
                DependencyEntity dependencyObj = new DependencyEntity();
                dependencyObj.setSite(site);
                dependencyObj.setSourcePath(getCleanPath(path));
                dependencyObj.setTargetPath(getCleanPath(file));
                dependencyObj.setType(dependencyType);
                dependencyEntities.add(dependencyObj);
                extractedPaths.add(file);
            }
        }
        return dependencyEntities;
    }

    private String getCleanPath(String path) {
        String cleanPath = path.replaceAll("//", "/");
        return cleanPath;
    }

    private void insertDependencies(List<DependencyEntity> dependencyEntities) {
        logger.debug("Insert list of dependency entities into database");
        if (CollectionUtils.isNotEmpty(dependencyEntities)) {
            Map<String, Object> params = new HashMap<>();
            params.put("dependencies", dependencyEntities);
            dependencyMapper.insertList(params);
        }
    }

    @Override
    public Set<String> upsertDependencies(String site, List<String> paths) throws SiteNotFoundException, ContentNotFoundException, ServiceException {
        Set<String> toRet = new HashSet<String>();
        List<DependencyEntity> dependencyEntities = new ArrayList<>();
        StringBuilder sbPaths = new StringBuilder();
        logger.debug("Resolving dependencies for list of paths.");
        for (String path : paths) {
            sbPaths.append("\n").append(path);
            logger.debug("Resolving dependencies for content site: " + site + " path: " + path);
            Map<String, Set<String>> dependencies = dependencyResolver.resolve(site, path);
            if (dependencies != null) {
                logger.debug("Found " + dependencies.size() + " dependencies. Create entities to insert into database.");
                for (String type : dependencies.keySet()) {
                    dependencyEntities.addAll(createDependencyEntities(site, path, dependencies.get(type), type, toRet));
                }
            }
        }
        logger.debug("Preparing transaction for database updates.");
        DefaultTransactionDefinition defaultTransactionDefinition = new DefaultTransactionDefinition();
        defaultTransactionDefinition.setName("upsertDependencies");
        logger.debug("Starting transaction.");
        TransactionStatus txStatus = transactionManager.getTransaction(defaultTransactionDefinition);
        try {
            logger.debug("Delete all source dependencies for list of paths site: " + site);
            deleteAllSourceDependencies(site, paths);
            logger.debug("Insert all extracted dependencies entries lof list of paths for site: " + site);
            insertDependencies(dependencyEntities);
            logger.debug("Committing transaction.");
            transactionManager.commit(txStatus);
        } catch (Exception e) {
            logger.debug("Rolling back transaction.");
            transactionManager.rollback(txStatus);
            throw new ServiceException("Failed to upsert dependencies for site: " + site + " paths: " + sbPaths.toString(), e);
        }

        return toRet;
    }

    private void deleteAllSourceDependencies(String site, List<String> paths) {
        for (String path : paths) {
            deleteAllSourceDependencies(site, path);
        }
    }

    @Override
    public Set<String> getPublishingDepenencies(String site, String path) throws SiteNotFoundException, ContentNotFoundException, ServiceException {
        logger.debug("Get publishing dependencies for site: " + site + " path:" + path);
        List<String> paths = new ArrayList<String>();
        paths.add(path);
        return getPublishingDepenencies(site, paths);
    }

    protected  Set<String> getMandatoryParents(String site, List<String> paths) {
        logger.debug("Get mandatory parents for list of paths");
        Set<String> parentPaths = new HashSet<String>();
        for (String path : paths) {
            parentPaths.addAll(getMandatoryParent(site, path));
        }
        return parentPaths;
    }

    protected Set<String> getMandatoryParent(String site, String path) {
        Set<String> parentPaths = new HashSet<String>();
        int idx = path.lastIndexOf("/" + DmConstants.INDEX_FILE);
        if (idx > 0) {
            path = path.substring(0, idx);
        }
        logger.debug("Calculate parent url for " + path);
        String parentPath = ContentUtils.getParentUrl(path);
        if (StringUtils.isNotEmpty(parentPath)) {
            logger.debug("If parent exists and it is NEW or RENAMED it is mandatory.");
            if (contentService.contentExists(site, parentPath)) {
                ContentItemTO item = contentService.getContentItem(site, parentPath);
                if (item.isNew() || objectMetadataManager.isRenamed(site, item.getUri())) {
                    logger.debug("Parent exists and it is NEW or RENAMED, it is mandatory.");
                    parentPaths.add(item.getUri());
                    parentPaths.addAll(getMandatoryParent(site, item.getUri()));
                }
            }
        }
        return parentPaths;
    }

    @Override
    public Set<String> getPublishingDepenencies(String site, List<String> paths) throws SiteNotFoundException, ContentNotFoundException, ServiceException {
        Map<String, Object> params = new HashMap<String, Object>();

        Set<String> toRet = new HashSet<String>();
        Set<String> pathsParams = new HashSet<String>();
        Set<String> parentPaths = getMandatoryParents(site, paths);

        toRet.addAll(parentPaths);

        // NEW
        logger.debug("Get all dependencies that are NEW");
        pathsParams.addAll(paths);
        pathsParams.addAll(parentPaths);
        boolean exitCondition = false;
        do {
            params = new HashMap<String, Object>();
            params.put("site", site);
            params.put("paths", pathsParams);
            params.put("states", State.NEW_STATES);
            List<String> deps = dependencyMapper.getPublishingDependenciesForList(params);
            exitCondition = !toRet.addAll(deps);
            pathsParams.clear();
            pathsParams.addAll(deps);
        } while (!exitCondition);

        logger.debug("Get all item specific dependencies that are edited");
        Collection<State> onlyEditStates = CollectionUtils.removeAll(State.CHANGE_SET_STATES, State.NEW_STATES);
        pathsParams.clear();
        pathsParams.addAll(paths);
        pathsParams.addAll(parentPaths);
        do {
            params = new HashMap<String, Object>();
            params.put("site", site);
            params.put("paths", pathsParams);
            params.put("states", onlyEditStates);
            List<String> deps = dependencyMapper.getPublishingDependenciesForList(params);
            Set<String> filtered = new HashSet<String>();
            filtered.addAll(deps);
            filtered = filterItemSpecificDependencies(filtered);
            exitCondition = !toRet.addAll(filtered);
            pathsParams.clear();
            pathsParams.addAll(filtered);
        } while (!exitCondition);

        return toRet;
    }

    private Set<String> filterItemSpecificDependencies(Set<String> paths) {
        Set<String> toRet = new HashSet<String>();
        for (String path : paths) {
            for (String itemSpecificDependency : itemSpecificDependencies) {
                Pattern p = Pattern.compile(itemSpecificDependency);
                Matcher m = p.matcher(path);
                if (m.matches()) {
                    toRet.add(path);
                    break;
                }
            }
        }
        return toRet;
    }

    @Override
    public Set<String> getItemSpecificDependencies(String site, String path, int depth) throws SiteNotFoundException, ContentNotFoundException, ServiceException {
        // Check if site exists
        if (!siteService.exists(site)) {
            throw new SiteNotFoundException();
        }

        // Check if content exists
        if (!contentService.contentExists(site, path)) {
            throw new ContentNotFoundException();
        }

        Map<String, Object> params = new HashMap<String, Object>();
        Set<String> toRet = new HashSet<String>();
        Set<String> paths = new HashSet<String>();
        boolean exitCondition = false;
        paths.add(path);
        if (depth < 0) {
            do {
                params = new HashMap<String, Object>();
                params.put("site", site);
                params.put("paths", paths);
                List<String> deps = dependencyMapper.getDependenciesForList(params);
                Set<String> filtered = new HashSet<String>();
                filtered.addAll(deps);
                filtered = filterItemSpecificDependencies(filtered);
                exitCondition = !toRet.addAll(filtered);
                paths.clear();
                paths.addAll(filtered);
            } while (!exitCondition);
        } else {
            int d = depth;
            while (d-- > 0) {
                params = new HashMap<String, Object>();
                params.put("site", site);
                params.put("paths", paths);
                List<String> deps = dependencyMapper.getDependenciesForList(params);
                Set<String> filtered = new HashSet<String>();
                filtered.addAll(deps);
                filtered = filterItemSpecificDependencies(filtered);
                exitCondition = !toRet.addAll(filtered);
                paths.clear();
                paths.addAll(filtered);
                if (exitCondition) break;
            }
        }
        return toRet;
    }

    @Override
    public Set<String> getItemDependencies(String site, String path, int depth) throws SiteNotFoundException, ContentNotFoundException, ServiceException {
        // Check if site exists
        if (!siteService.exists(site)) {
            throw new SiteNotFoundException();
        }

        // Check if content exists
        if (!contentService.contentExists(site, path)) {
            throw new ContentNotFoundException();
        }

        logger.debug("Get dependency items for content " + path + " for site " + site);

        Map<String, Object> params = new HashMap<String, Object>();
        Set<String> toRet = new HashSet<String>();
        Set<String> paths = new HashSet<String>();
        paths.add(path);
        boolean exitCondition;
        if (depth < 0) {
            do {
                params = new HashMap<String, Object>();
                params.put("site", site);
                params.put("paths", paths);
                List<String> deps = dependencyMapper.getDependenciesForList(params);
                exitCondition = !toRet.addAll(deps);
                paths.clear();
                paths.addAll(deps);
            } while (!exitCondition);
        } else {
            int d = depth;
            while (d-- > 0) {
                params = new HashMap<String, Object>();
                params.put("site", site);
                params.put("paths", paths);
                List<String> deps = dependencyMapper.getDependenciesForList(params);
                exitCondition = !toRet.addAll(deps);
                if (exitCondition) break;
            }
        }
        return toRet;
    }

    @Override
    public Set<String> getItemsDependingOn(String site, String path, int depth) throws SiteNotFoundException, ContentNotFoundException, ServiceException {
        // Check if site exists
        if (!siteService.exists(site)) {
            throw new SiteNotFoundException();
        }

        // Check if content exists
        if (!contentService.contentExists(site, path)) {
            throw new ContentNotFoundException();
        }

        logger.debug("Get items depending on content " + path + " for site " + site);
        Set<String> toRet = new HashSet<String>();
        Set<String> paths = new HashSet<String>();
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("site", site);
        params.put("targetPath", path);
        if (depth < 0) {
            paths.add(path);
            do {
                params = new HashMap<String, Object>();
                params.put("site", site);
                params.put("paths", paths);
                List<String> deps = dependencyMapper.getItemsDependingOn(params);
                toRet.addAll(deps);
                paths.clear();
                paths.addAll(deps);
            } while (paths.size() > 0);
        } else {
            int d = depth;
            paths.add(path);
            while (d-- > 0) {
                params = new HashMap<String, Object>();
                params.put("site", site);
                params.put("paths", paths);
                List<String> deps = dependencyMapper.getItemsDependingOn(params);
                toRet.addAll(deps);
                paths.clear();
                paths.addAll(deps);
            }
        }

        return toRet;
    }

    @Override
    public Set<String> moveDependencies(String site, String oldPath, String newPath) throws SiteNotFoundException, ContentNotFoundException, ServiceException {
        // Check if site exists
        if (!siteService.exists(site)) {
            throw new SiteNotFoundException();
        }

        // Check if content exists
        if (!contentService.contentExists(site, newPath)) {
            throw new ContentNotFoundException();
        }

        Map<String, String> params = new HashMap<String, String>();
        params.put("siteId", site);
        params.put("oldPath", oldPath);
        params.put("newPath", newPath);
        dependencyMapper.moveDependency(params);

        return getItemDependencies(site, newPath, 1);
    }

    @Override
    public void deleteItemDependencies(@ValidateStringParam(name = "site") String site, @ValidateSecurePathParam(name = "path") String path) throws SiteNotFoundException, ContentNotFoundException, ServiceException {
        // Check if site exists
        if (!siteService.exists(site)) {
            throw new SiteNotFoundException();
        }

        // Check if content exists
        if (!contentService.contentExists(site, path)) {
            throw new ContentNotFoundException();
        }

        logger.debug("Delete dependencies for item - site: " + site + " path: " + path);
        Map<String, String> params = new HashMap<>();
        params.put("site", site);
        params.put("path", path);
        dependencyMapper.deleteDependenciesForSiteAndPath(params);
    }

    @Override
    public void deleteItemDependencies(String site, List<String> paths) throws SiteNotFoundException, ContentNotFoundException, ServiceException {
        // Check if site exists
        if (!siteService.exists(site)) {
            throw new SiteNotFoundException();
        }

        StringBuilder sbPaths = new StringBuilder();
        for (String path : paths) {
            // Check if content exists
            if (!contentService.contentExists(site, path)) {
                throw new ContentNotFoundException();
            }
            sbPaths.append("\n").append(path);
        }

        logger.debug("Delete dependencies for item - site: " + site + " paths: " + sbPaths.toString());
        Map<String, Object> params = new HashMap<>();
        params.put("site", site);
        params.put("path", paths);
        dependencyMapper.deleteDependenciesForSiteAndListOfPaths(params);
    }

    @Override
    public void deleteSiteDependencies(@ValidateStringParam(name = "site") String site) throws SiteNotFoundException, ServiceException {
        // Check if site exists
        if (!siteService.exists(site)) {
            throw new SiteNotFoundException();
        }

        logger.debug("Delete all dependencies for site " + site);
        Map<String, String> params = new HashMap<>();
        params.put("site", site);
        dependencyMapper.deleteDependenciesForSite(params);
    }

    public SiteService getSiteService() { return siteService; }
    public void setSiteService(SiteService siteService) { this.siteService = siteService; }

    public ContentService getContentService() { return contentService; }
    public void setContentService(ContentService contentService) { this.contentService = contentService; }

    public DependencyResolver getDependencyResolver() { return dependencyResolver; }
    public void setDependencyResolver(DependencyResolver dependencyResolver) { this.dependencyResolver = dependencyResolver; }

    public PlatformTransactionManager getTransactionManager() { return transactionManager; }
    public void setTransactionManager(PlatformTransactionManager transactionManager) { this.transactionManager = transactionManager; }

    public List<String> getItemSpecificDependencies() { return itemSpecificDependencies; }
    public void setItemSpecificDependencies(List<String> itemSpecificDependencies) { this.itemSpecificDependencies = itemSpecificDependencies; }

    public ObjectMetadataManager getObjectMetadataManager() { return objectMetadataManager; }
    public void setObjectMetadataManager(ObjectMetadataManager objectMetadataManager) { this.objectMetadataManager = objectMetadataManager; }
}