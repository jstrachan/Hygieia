package com.capitalone.dashboard.collector;

import com.capitalone.dashboard.model.Build;
import com.capitalone.dashboard.model.HudsonCollector;
import com.capitalone.dashboard.model.HudsonJob;
import com.capitalone.dashboard.repository.BaseCollectorRepository;
import com.capitalone.dashboard.repository.BuildRepository;
import com.capitalone.dashboard.repository.HudsonCollectorRepository;
import com.capitalone.dashboard.repository.HudsonJobRepository;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CollectorTask that fetches Build information from Hudson
 */
@Component
public class HudsonCollectorTask extends CollectorTask<HudsonCollector> {

    private static final Log LOG = LogFactory.getLog(HudsonCollectorTask.class);

    private final HudsonCollectorRepository hudsonCollectorRepository;
    private final HudsonJobRepository hudsonJobRepository;
    private final BuildRepository buildRepository;
    private final HudsonClient hudsonClient;
    private final HudsonSettings hudsonSettings;

    @Autowired
    public HudsonCollectorTask(TaskScheduler taskScheduler,
                               HudsonCollectorRepository hudsonCollectorRepository,
                               HudsonJobRepository hudsonJobRepository,
                               BuildRepository buildRepository,
                               HudsonClient hudsonClient,
                               HudsonSettings hudsonSettings) {
        super(taskScheduler, "Hudson");
        LOG.info("Starting with HudsonSettings: " + hudsonSettings);

        this.hudsonCollectorRepository = hudsonCollectorRepository;
        this.hudsonJobRepository = hudsonJobRepository;
        this.buildRepository = buildRepository;
        this.hudsonClient = hudsonClient;
        this.hudsonSettings = hudsonSettings;
    }

    @Override
    public HudsonCollector getCollector() {
        return HudsonCollector.prototype(hudsonSettings.getServers());
    }

    @Override
    public BaseCollectorRepository<HudsonCollector> getCollectorRepository() {
        return hudsonCollectorRepository;
    }

    @Override
    public String getCron() {
        return hudsonSettings.getCron();
    }

    @Override
    public void collect(HudsonCollector collector) {

        for (String instanceUrl : collector.getBuildServers()) {
            logInstanceBanner(instanceUrl);

            long start = System.currentTimeMillis();

            Map<HudsonJob, Set<Build>> buildsByJob = hudsonClient.getInstanceJobs(instanceUrl);
            log("Fetched jobs", start);

            addNewJobs(buildsByJob.keySet(), collector);

            addNewBuilds(enabledJobs(collector, instanceUrl), buildsByJob);

            log("Finished", start);
        }

    }

    /**
     * Iterates over the enabled build jobs and adds new builds to the database.
     *
     * @param enabledJobs list of enabled {@link HudsonJob}s
     * @param buildsByJob maps a {@link HudsonJob} to a set of {@link Build}s.
     */
    private void addNewBuilds(List<HudsonJob> enabledJobs, Map<HudsonJob, Set<Build>> buildsByJob) {
        long start = System.currentTimeMillis();
        int count = 0;

        for (HudsonJob job : enabledJobs) {

            for (Build buildSummary : nullSafe(buildsByJob.get(job))) {

                if (isNewBuild(job, buildSummary)) {
                    Build build = hudsonClient.getBuildDetails(buildSummary.getBuildUrl());
                    if (build != null) {
                        build.setCollectorItemId(job.getId());
                        buildRepository.save(build);
                        count++;
                    }
                }

            }
        }
        log("New builds", start, count);
    }

    private Set<Build> nullSafe(Set<Build> builds) {
        return builds == null ? new HashSet<Build>() : builds;
    }

    /**
     * Adds new {@link HudsonJob}s to the database as disabled jobs.
     *
     * @param jobs list of {@link HudsonJob}s
     * @param collector the {@link HudsonCollector}
     */
    private void addNewJobs(Set<HudsonJob> jobs, HudsonCollector collector) {
        long start = System.currentTimeMillis();
        int count = 0;

        for (HudsonJob job : jobs) {

            if (isNewJob(collector, job)) {
                job.setCollectorId(collector.getId());
                job.setEnabled(false); // Do not enable for collection. Will be enabled when added to dashboard
                job.setDescription(job.getJobName());
                hudsonJobRepository.save(job);
                count++;
            }

        }
        log("New jobs", start, count);
    }

    private List<HudsonJob> enabledJobs(HudsonCollector collector, String instanceUrl) {
        return hudsonJobRepository.findEnabledHudsonJobs(collector.getId(), instanceUrl);
    }

    private boolean isNewJob(HudsonCollector collector, HudsonJob job) {
        return hudsonJobRepository.findHudsonJob(
                collector.getId(), job.getInstanceUrl(), job.getJobName()) == null;
    }

    private boolean isNewBuild(HudsonJob job, Build build) {
        return buildRepository.findByCollectorItemIdAndNumber(job.getId(), build.getNumber()) == null;
    }

    private void log(String marker, long start) {
        log(marker, start, null);
    }

    private void log(String text, long start, Integer count) {
        long end = System.currentTimeMillis();
        String elapsed = ((end - start) / 1000) + "s";
        String token2 = "";
        String token3;
        if (count == null) {
            token3 = StringUtils.leftPad(elapsed, 30 - text.length() );
        } else {
            String countStr = count.toString();
            token2 = StringUtils.leftPad(countStr, 20 - text.length() );
            token3 = StringUtils.leftPad(elapsed, 10 );
        }
        LOG.info(text + token2 + token3);
    }

    private void logInstanceBanner(String instanceUrl) {
        LOG.info("------------------------------");
        LOG.info(instanceUrl);
        LOG.info("------------------------------");
    }
}
