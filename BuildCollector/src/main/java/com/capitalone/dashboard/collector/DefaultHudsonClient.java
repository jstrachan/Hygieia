package com.capitalone.dashboard.collector;

import com.capitalone.dashboard.model.Build;
import com.capitalone.dashboard.model.BuildStatus;
import com.capitalone.dashboard.model.HudsonJob;
import com.capitalone.dashboard.model.SCM;
import com.capitalone.dashboard.util.Supplier;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestOperations;

import java.net.URI;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * HudsonClient implementation that uses RestTemplate and JSONSimple to
 * fetch information from Hudson instances.
 */
@Component
public class DefaultHudsonClient implements HudsonClient {
    private static final Log LOG = LogFactory.getLog(DefaultHudsonClient.class);

    private final RestOperations rest;
    private final HudsonSettings settings;

    private static final String JOBS_URL_SUFFIX = "/api/json?tree=jobs[name,url,builds[number,url]]";

    private static final String[] CHANGE_SET_ITEMS_TREE = new String[] {
            "user",
            "author[fullName]",
            "revision",
            "id",
            "msg",
            "timestamp",
            "date",
            "paths[file]"
    };

    private static final String[] BUILD_DETAILS_TREE = new String[] {
            "number",
            "url",
            "timestamp",
            "duration",
            "building",
            "result",
            "culprits[fullName]",
            "changeSet[items[" + StringUtils.join(CHANGE_SET_ITEMS_TREE, ",") + "]",
            "revisions[module,revision]]"
    };

    private static final String BUILD_DETAILS_URL_SUFFIX = "/api/json?tree=" + StringUtils.join(BUILD_DETAILS_TREE, ",");

    @Autowired
    public DefaultHudsonClient(Supplier<RestOperations> restOperationsSupplier, HudsonSettings settings) {
        this.rest = restOperationsSupplier.get();
        this.settings = settings;
    }

    @Override
    public Map<HudsonJob, Set<Build>> getInstanceJobs(String instanceUrl) {
        Map<HudsonJob, Set<Build>> result = new LinkedHashMap<>();
        try {
            String url = StringUtils.removeEnd(instanceUrl, "/") + JOBS_URL_SUFFIX;
            String returnJSON = rest.getForObject(URI.create(url), String.class);
            JSONParser parser = new JSONParser();

            try {
                JSONObject object = (JSONObject) parser.parse(returnJSON);

                for (Object job : getJsonArray(object, "jobs")) {
                    JSONObject jsonJob = (JSONObject) job;

                    HudsonJob hudsonJob = new HudsonJob();
                    hudsonJob.setInstanceUrl(instanceUrl);
                    hudsonJob.setJobName(getString(jsonJob, "name"));
                    hudsonJob.setJobUrl(getString(jsonJob, "url"));

                    Set<Build> builds = new LinkedHashSet<>();
                    result.put(hudsonJob, builds);

                    for (Object build : getJsonArray(jsonJob, "builds")) {
                        JSONObject jsonBuild = (JSONObject) build;

                        // A basic Build object. This will be fleshed out later if this is a new Build.
                        String buildNumber = jsonBuild.get("number").toString();
                        if (!buildNumber.equals("0")) {
                            Build hudsonBuild = new Build();
                            hudsonBuild.setNumber(buildNumber);
                            hudsonBuild.setBuildUrl(getString(jsonBuild, "url"));
                            builds.add(hudsonBuild);
                        }
                    }
                }
            } catch (ParseException e) {
                LOG.error("Parsing jobs on instance: " + instanceUrl, e);
            }
        } catch (RestClientException rce) {
            LOG.error(rce);
        }

        return result;
    }

    @Override
    public Build getBuildDetails(String buildUrl) {
        try {
            String url = StringUtils.removeEnd(buildUrl, "/") + BUILD_DETAILS_URL_SUFFIX;
            String returnJSON = rest.getForObject(URI.create(url), String.class);
            JSONParser parser = new JSONParser();

            try {
                JSONObject buildJson = (JSONObject) parser.parse(returnJSON);
                Boolean building = (Boolean) buildJson.get("building");

                // Ignore jobs that are building
                if (!building) {
                    Build build = new Build();
                    build.setNumber(buildJson.get("number").toString());
                    build.setBuildUrl(buildUrl);
                    build.setTimestamp(System.currentTimeMillis());
                    build.setStartTime((Long) buildJson.get("timestamp"));
                    build.setDuration((Long) buildJson.get("duration"));
                    build.setEndTime(build.getStartTime() + build.getDuration());
                    build.setBuildStatus(getBuildStatus(buildJson));
                    build.setStartedBy(firstCulprit(buildJson));
                    if (settings.isSaveLog()) {
                        build.setLog(getLog(buildUrl));
                    }

                    addChangeSets(build, buildJson);
                    return build;
                }

            } catch (ParseException e) {
                LOG.error("Parsing build: " + buildUrl, e);
            }
        } catch (RestClientException rce) {
            LOG.error(rce);
        }

        return null;
    }

    /**
     * Grabs changeset information for the given build.
     *
     * @param build a Build
     * @param buildJson the build JSON object
     */
    private void addChangeSets(Build build, JSONObject buildJson) {
        JSONObject changeSet = (JSONObject) buildJson.get("changeSet");

        Map<String, String> revisionToUrl = new HashMap<>();

        // Build a map of revision to module (scm url). This is not always
        // provided by the Hudson API, but we can use it if available.
        for (Object revision : getJsonArray(changeSet, "revisions")) {
            JSONObject json = (JSONObject) revision;
            revisionToUrl.put(json.get("revision").toString(), getString(json, "module"));
        }

        for (Object item : getJsonArray(changeSet, "items")) {
            JSONObject jsonItem = (JSONObject) item;
            SCM scm = new SCM();
            scm.setScmAuthor(getCommitAuthor(jsonItem));
            scm.setScmCommitLog(getString(jsonItem, "msg"));
            scm.setScmCommitTimestamp(getCommitTimestamp(jsonItem));
            scm.setScmRevisionNumber(getRevision(jsonItem));
            scm.setScmUrl(revisionToUrl.get(scm.getScmRevisionNumber()));
            scm.setNumberOfChanges(getJsonArray(jsonItem, "paths").size());

            build.getSourceChangeSet().add(scm);
        }
    }

    ////// Helpers

    private long getCommitTimestamp(JSONObject jsonItem) {
        if (jsonItem.get("timestamp") != null) {
            return (Long) jsonItem.get("timestamp");
        } else if (jsonItem.get("date") != null) {
            String dateString = (String) jsonItem.get("date");
            try {
                return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS").parse(dateString).getTime();
            } catch (java.text.ParseException e) {
                // Try an alternate date format...looks like this one is used by Git
                try {
                    return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z").parse(dateString).getTime();
                } catch (java.text.ParseException e1) {
                    LOG.error("Invalid date string: " + dateString, e);
                }
            }
        }
        return 0;
    }

    private String getString(JSONObject json, String key) {
        return (String) json.get(key);
    }

    private String getRevision(JSONObject jsonItem) {
        // Use revision if provided, otherwise use id
        Long revision = (Long) jsonItem.get("revision");
        return revision == null ? getString(jsonItem, "id") : revision.toString();
    }

    private JSONArray getJsonArray(JSONObject json, String key) {
        Object array = json != null ? json.get(key) : null;
        return array == null ? new JSONArray() : (JSONArray) array;
    }

    private String firstCulprit(JSONObject buildJson) {
        JSONArray culprits = getJsonArray(buildJson, "culprits");
        if (culprits.isEmpty()) {
            return null;
        }
        JSONObject culprit = (JSONObject) culprits.get(0);
        return getFullName(culprit);
    }

    private String getFullName(JSONObject author) {
        return getString(author, "fullName");
    }

    private String getCommitAuthor(JSONObject jsonItem) {
        // Use user if provided, otherwise use author.fullName
        JSONObject author = (JSONObject) jsonItem.get("author");
        return author == null ? getString(jsonItem, "user") : getFullName(author);
    }

    private BuildStatus getBuildStatus(JSONObject buildJson) {
        String status = buildJson.get("result").toString();
        switch(status) {
            case "SUCCESS": return BuildStatus.Success;
            case "UNSTABLE": return BuildStatus.Unstable;
            case "FAILURE": return BuildStatus.Failure;
            case "ABORTED": return BuildStatus.Aborted;
            default: return BuildStatus.Unknown;
        }
    }

    private String getLog(String buildUrl) {
        return rest.getForObject(URI.create(buildUrl + "consoleText"), String.class);
    }
}
