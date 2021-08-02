package de.dmeiners.sonar.prometheus;

import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.Gauge;
import io.prometheus.client.exporter.common.TextFormat;
import org.sonar.api.config.Configuration;
import org.sonar.api.measures.CoreMetrics;
import org.sonar.api.measures.Metric;
import org.sonar.api.resources.Qualifiers;
import org.sonar.api.server.ws.WebService;
import org.sonarqube.ws.Components;
import org.sonarqube.ws.Measures;
import org.sonarqube.ws.ProjectBranches;
import org.sonarqube.ws.client.WsClient;
import org.sonarqube.ws.client.WsClientFactories;
import org.sonarqube.ws.client.components.SearchRequest;
import org.sonarqube.ws.client.measures.ComponentRequest;
import org.sonarqube.ws.client.projectbranches.ListRequest;

import org.sonar.api.utils.log.*;

import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.*;
import java.util.stream.Collectors;

import static java.util.Objects.nonNull;

public class PrometheusWebService implements WebService {
    private static final Logger LOGGER = Loggers.get(PrometheusWebService.class);

    static final Set<Metric<?>> SUPPORTED_METRICS = new HashSet<>();
    static final String CONFIG_PREFIX = "prometheus.export.";
    private static final String METRIC_PREFIX = "sonarqube_";

    private final Configuration configuration;
    private final Map<String, Gauge> gauges = new HashMap<>();
    private final Set<Metric<?>> enabledMetrics = new HashSet<>();

    static {
        // ISSUES
        SUPPORTED_METRICS.add(CoreMetrics.VIOLATIONS);
        SUPPORTED_METRICS.add(CoreMetrics.BLOCKER_VIOLATIONS);
        SUPPORTED_METRICS.add(CoreMetrics.CRITICAL_VIOLATIONS);
        SUPPORTED_METRICS.add(CoreMetrics.MAJOR_VIOLATIONS);
        SUPPORTED_METRICS.add(CoreMetrics.MINOR_VIOLATIONS);
        SUPPORTED_METRICS.add(CoreMetrics.INFO_VIOLATIONS);

        SUPPORTED_METRICS.add(CoreMetrics.NEW_VIOLATIONS);
        SUPPORTED_METRICS.add(CoreMetrics.NEW_BLOCKER_VIOLATIONS);
        SUPPORTED_METRICS.add(CoreMetrics.NEW_CRITICAL_VIOLATIONS);
        SUPPORTED_METRICS.add(CoreMetrics.NEW_MAJOR_VIOLATIONS);
        SUPPORTED_METRICS.add(CoreMetrics.NEW_MINOR_VIOLATIONS);
        SUPPORTED_METRICS.add(CoreMetrics.NEW_INFO_VIOLATIONS);

        SUPPORTED_METRICS.add(CoreMetrics.FALSE_POSITIVE_ISSUES);
        SUPPORTED_METRICS.add(CoreMetrics.OPEN_ISSUES);
        SUPPORTED_METRICS.add(CoreMetrics.CONFIRMED_ISSUES);
        SUPPORTED_METRICS.add(CoreMetrics.REOPENED_ISSUES);


        // Maintainability
        SUPPORTED_METRICS.add(CoreMetrics.CODE_SMELLS);
        SUPPORTED_METRICS.add(CoreMetrics.NEW_CODE_SMELLS);
        SUPPORTED_METRICS.add(CoreMetrics.SQALE_RATING);
        SUPPORTED_METRICS.add(CoreMetrics.TECHNICAL_DEBT);
        SUPPORTED_METRICS.add(CoreMetrics.NEW_TECHNICAL_DEBT);
        SUPPORTED_METRICS.add(CoreMetrics.SQALE_DEBT_RATIO);
        SUPPORTED_METRICS.add(CoreMetrics.NEW_SQALE_DEBT_RATIO);

        // Reliability
        SUPPORTED_METRICS.add(CoreMetrics.BUGS);
        SUPPORTED_METRICS.add(CoreMetrics.NEW_BUGS);
        SUPPORTED_METRICS.add(CoreMetrics.RELIABILITY_RATING);
        SUPPORTED_METRICS.add(CoreMetrics.RELIABILITY_REMEDIATION_EFFORT);
        SUPPORTED_METRICS.add(CoreMetrics.NEW_RELIABILITY_REMEDIATION_EFFORT);

        // Security
        SUPPORTED_METRICS.add(CoreMetrics.VULNERABILITIES);
        SUPPORTED_METRICS.add(CoreMetrics.NEW_VULNERABILITIES);
        SUPPORTED_METRICS.add(CoreMetrics.SECURITY_RATING);
        SUPPORTED_METRICS.add(CoreMetrics.SECURITY_REMEDIATION_EFFORT);
        SUPPORTED_METRICS.add(CoreMetrics.NEW_SECURITY_REMEDIATION_EFFORT);
        
        // Size
        SUPPORTED_METRICS.add(CoreMetrics.CLASSES);
        SUPPORTED_METRICS.add(CoreMetrics.COMMENT_LINES);
        SUPPORTED_METRICS.add(CoreMetrics.COMMENT_LINES_DENSITY);
        SUPPORTED_METRICS.add(CoreMetrics.FILES);
        SUPPORTED_METRICS.add(CoreMetrics.LINES);
        SUPPORTED_METRICS.add(CoreMetrics.GENERATED_LINES);
        SUPPORTED_METRICS.add(CoreMetrics.GENERATED_NCLOC);
        SUPPORTED_METRICS.add(CoreMetrics.NCLOC);
        //SUPPORTED_METRICS.add(CoreMetrics.NCLOC_LANGUAGE_DISTRIBUTION);
        
        SUPPORTED_METRICS.add(CoreMetrics.FUNCTIONS);
        SUPPORTED_METRICS.add(CoreMetrics.STATEMENTS);
        
        SUPPORTED_METRICS.add(CoreMetrics.LINES_TO_COVER);

        SUPPORTED_METRICS.add(CoreMetrics.NEW_LINES);
        SUPPORTED_METRICS.add(CoreMetrics.NEW_LINES_TO_COVER);

        SUPPORTED_METRICS.add(CoreMetrics.COVERAGE);
        SUPPORTED_METRICS.add(CoreMetrics.COMPLEXITY);
        SUPPORTED_METRICS.add(CoreMetrics.DUPLICATED_LINES_DENSITY);
        SUPPORTED_METRICS.add(CoreMetrics.DUPLICATED_BLOCKS);
        SUPPORTED_METRICS.add(CoreMetrics.DUPLICATED_LINES);
        SUPPORTED_METRICS.add(CoreMetrics.DUPLICATED_FILES);
        SUPPORTED_METRICS.add(CoreMetrics.COGNITIVE_COMPLEXITY);
        SUPPORTED_METRICS.add(CoreMetrics.NEW_COVERAGE);

        SUPPORTED_METRICS.add(CoreMetrics.PROJECTS);
    }

    public PrometheusWebService(Configuration configuration) {

        this.configuration = configuration;
    }

    @Override
    public void define(Context context) {

        updateEnabledMetrics();
        updateEnabledGauges();

        NewController controller = context.createController("api/prometheus");
        controller.setDescription("Prometheus Exporter");

        controller.createAction("metrics")
                .setHandler((request, response) -> {

                    updateEnabledMetrics();
                    updateEnabledGauges();

                    if (!this.enabledMetrics.isEmpty()) {

                        WsClient wsClient = WsClientFactories.getLocal().newClient(request.localConnector());

                        List<Components.Component> projects = getProjects(wsClient);
                        projects.forEach(project -> {

                            ProjectBranches.ListWsResponse branchResponse = getBranches(wsClient, project);
                            branchResponse.getBranchesList().forEach(branch -> {
    
                                Measures.ComponentWsResponse wsResponse = getMeasures(wsClient, project, branch.getName());
                                wsResponse.getComponent().getMeasuresList().forEach(measure -> {
                                    if (this.gauges.containsKey(measure.getMetric())) {
                                        try {
                                            if(measure.hasValue()){
                                                this.gauges.get(measure.getMetric()).labels(project.getKey(), project.getName(), branch.getName()).set(Double.valueOf(measure.getValue()));
                                            }else{
                                                this.gauges.get(measure.getMetric()).labels(project.getKey(), project.getName(), branch.getName()).set(Double.valueOf(measure.getPeriods().getPeriodsValueList().get(0).getValue()));
                                            }
                                        } catch(Exception e) {
                                            // ignore non-numeric invalid values
                                            LOGGER.warn(String.format("["+project.getKey() +"_" + project.getName() + "]:" + measure.getMetric() + "=>" +measure.getValue()), e);
                                        }
                                    }
                                });
                            });
                        });
                    }

                    OutputStream output = response.stream()
                            .setMediaType(TextFormat.CONTENT_TYPE_004)
                            .setStatus(200)
                            .output();

                    try (OutputStreamWriter writer = new OutputStreamWriter(output)) {

                        TextFormat.write004(writer, CollectorRegistry.defaultRegistry.metricFamilySamples());
                    }

                });

        controller.done();
    }

    private void updateEnabledMetrics() {

        Map<Boolean, List<Metric<?>>> byEnabledState = SUPPORTED_METRICS.stream()
                .collect(Collectors.groupingBy(metric -> this.configuration.getBoolean(CONFIG_PREFIX + metric.getKey()).orElse(false)));

        this.enabledMetrics.clear();

        if (nonNull(byEnabledState.get(true))) {
            this.enabledMetrics.addAll(byEnabledState.get(true));
        }
    }

    private void updateEnabledGauges() {

        CollectorRegistry.defaultRegistry.clear();

        this.enabledMetrics.forEach(metric -> gauges.put(metric.getKey(), Gauge.build()
                .name(METRIC_PREFIX + metric.getKey())
                .help(metric.getDescription())
                .labelNames("key", "name", "branch")
                .register()));
    }

    private Measures.ComponentWsResponse getMeasures(WsClient wsClient, Components.Component project, String branchName) {

        List<String> metricKeys = this.enabledMetrics.stream()
                .map(Metric::getKey)
                .collect(Collectors.toList());

        return wsClient.measures().component(new ComponentRequest()
                .setComponent(project.getKey())
                .setBranch(branchName)
                .setMetricKeys(metricKeys));
    }

    private ProjectBranches.ListWsResponse getBranches(WsClient wsClient, Components.Component project){
        return wsClient.projectBranches().list(new ListRequest()
            .setProject(project.getKey()));
    }

    private List<Components.Component> getProjects(WsClient wsClient) {

        int pageSize = 500;
        int projectsCount = wsClient.components().search(new SearchRequest()
                        .setQualifiers(Collections.singletonList(Qualifiers.PROJECT))
                        .setPs(String.valueOf(pageSize))).getPaging().getTotal();

        List<Components.Component> componentList = new ArrayList<>();
        for(int i=1; i < (projectsCount/pageSize)+1; i++){
            componentList.addAll(wsClient.components().search(new SearchRequest()
                    .setQualifiers(Collections.singletonList(Qualifiers.PROJECT))
                    .setPs(String.valueOf(pageSize)).setP(String.valueOf(i)))
                    .getComponentsList());
        }

        return componentList;
    }
}
