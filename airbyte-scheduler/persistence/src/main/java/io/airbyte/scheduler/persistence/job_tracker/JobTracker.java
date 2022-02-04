/*
 * Copyright (c) 2021 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.scheduler.persistence.job_tracker;

import static java.util.Collections.emptyMap;
import static java.util.stream.Collectors.toMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMap.Builder;
import io.airbyte.analytics.TrackingClient;
import io.airbyte.commons.lang.Exceptions;
import io.airbyte.commons.map.MoreMaps;
import io.airbyte.config.JobConfig;
import io.airbyte.config.JobConfig.ConfigType;
import io.airbyte.config.StandardCheckConnectionOutput;
import io.airbyte.config.StandardDestinationDefinition;
import io.airbyte.config.StandardSourceDefinition;
import io.airbyte.config.StandardSync;
import io.airbyte.config.StandardSyncOperation;
import io.airbyte.config.StandardWorkspace;
import io.airbyte.config.persistence.ConfigNotFoundException;
import io.airbyte.config.persistence.ConfigRepository;
import io.airbyte.protocol.models.ConfiguredAirbyteCatalog;
import io.airbyte.protocol.models.ConfiguredAirbyteStream;
import io.airbyte.scheduler.models.Job;
import io.airbyte.scheduler.persistence.JobPersistence;
import io.airbyte.scheduler.persistence.WorkspaceHelper;
import io.airbyte.validation.json.JsonSchemaValidator;
import io.airbyte.validation.json.JsonValidationException;
import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.UUID;

public class JobTracker {

  public enum JobState {
    STARTED,
    SUCCEEDED,
    FAILED
  }

  public static final String MESSAGE_NAME = "Connector Jobs";
  public static final String CONFIG = "config";
  public static final String CATALOG = "catalog";
  public static final String OPERATION = "operation.";
  public static final String SET = "set";

  private final ConfigRepository configRepository;
  private final JobPersistence jobPersistence;
  private final WorkspaceHelper workspaceHelper;
  private final TrackingClient trackingClient;

  public JobTracker(final ConfigRepository configRepository, final JobPersistence jobPersistence, final TrackingClient trackingClient) {
    this(configRepository, jobPersistence, new WorkspaceHelper(configRepository, jobPersistence), trackingClient);
  }

  @VisibleForTesting
  JobTracker(final ConfigRepository configRepository,
             final JobPersistence jobPersistence,
             final WorkspaceHelper workspaceHelper,
             final TrackingClient trackingClient) {
    this.configRepository = configRepository;
    this.jobPersistence = jobPersistence;
    this.workspaceHelper = workspaceHelper;
    this.trackingClient = trackingClient;
  }

  public void trackCheckConnectionSource(final UUID jobId,
                                         final UUID sourceDefinitionId,
                                         final UUID workspaceId,
                                         final JobState jobState,
                                         final StandardCheckConnectionOutput output) {
    Exceptions.swallow(() -> {
      final ImmutableMap<String, Object> checkConnMetadata = generateCheckConnectionMetadata(output);
      final ImmutableMap<String, Object> jobMetadata = generateJobMetadata(jobId.toString(), ConfigType.CHECK_CONNECTION_SOURCE);
      final ImmutableMap<String, Object> sourceDefMetadata = generateSourceDefinitionMetadata(sourceDefinitionId);
      final ImmutableMap<String, Object> stateMetadata = generateStateMetadata(jobState);

      track(workspaceId, MoreMaps.merge(checkConnMetadata, jobMetadata, sourceDefMetadata, stateMetadata));
    });
  }

  public void trackCheckConnectionDestination(final UUID jobId,
                                              final UUID destinationDefinitionId,
                                              final UUID workspaceId,
                                              final JobState jobState,
                                              final StandardCheckConnectionOutput output) {
    Exceptions.swallow(() -> {
      final ImmutableMap<String, Object> checkConnMetadata = generateCheckConnectionMetadata(output);
      final ImmutableMap<String, Object> jobMetadata = generateJobMetadata(jobId.toString(), ConfigType.CHECK_CONNECTION_DESTINATION);
      final ImmutableMap<String, Object> destinationDefinitionMetadata = generateDestinationDefinitionMetadata(destinationDefinitionId);
      final ImmutableMap<String, Object> stateMetadata = generateStateMetadata(jobState);

      track(workspaceId, MoreMaps.merge(checkConnMetadata, jobMetadata, destinationDefinitionMetadata, stateMetadata));
    });
  }

  public void trackDiscover(final UUID jobId, final UUID sourceDefinitionId, final UUID workspaceId, final JobState jobState) {
    Exceptions.swallow(() -> {
      final ImmutableMap<String, Object> jobMetadata = generateJobMetadata(jobId.toString(), ConfigType.DISCOVER_SCHEMA);
      final ImmutableMap<String, Object> sourceDefMetadata = generateSourceDefinitionMetadata(sourceDefinitionId);
      final ImmutableMap<String, Object> stateMetadata = generateStateMetadata(jobState);

      track(workspaceId, MoreMaps.merge(jobMetadata, sourceDefMetadata, stateMetadata));
    });
  }

  // used for tracking all asynchronous jobs (sync and reset).
  public void trackSync(final Job job, final JobState jobState) {
    Exceptions.swallow(() -> {
      final ConfigType configType = job.getConfigType();
      final boolean allowedJob = configType == ConfigType.SYNC || configType == ConfigType.RESET_CONNECTION;
      Preconditions.checkArgument(allowedJob, "Job type " + configType + " is not allowed!");
      final long jobId = job.getId();
      final UUID connectionId = UUID.fromString(job.getScope());
      final StandardSourceDefinition sourceDefinition = configRepository.getSourceDefinitionFromConnection(connectionId);
      final UUID sourceDefinitionId = sourceDefinition.getSourceDefinitionId();
      final StandardDestinationDefinition destinationDefinition = configRepository.getDestinationDefinitionFromConnection(connectionId);
      final UUID destinationDefinitionId = destinationDefinition.getDestinationDefinitionId();

      final Map<String, Object> jobMetadata = generateJobMetadata(String.valueOf(jobId), configType, job.getAttemptsCount());
      final Map<String, Object> jobAttemptMetadata = generateJobAttemptMetadata(job.getId(), jobState);
      final Map<String, Object> sourceDefMetadata = generateSourceDefinitionMetadata(sourceDefinitionId);
      final Map<String, Object> destinationDefMetadata = generateDestinationDefinitionMetadata(destinationDefinitionId);
      final Map<String, Object> syncMetadata = generateSyncMetadata(connectionId);
      final Map<String, Object> stateMetadata = generateStateMetadata(jobState);
      final Map<String, Object> syncConfigMetadata = generateSyncConfigMetadata(
          job.getConfig(),
          sourceDefinition.getSpec().getConnectionSpecification(),
          destinationDefinition.getSpec().getConnectionSpecification());

      final UUID workspaceId = workspaceHelper.getWorkspaceForJobIdIgnoreExceptions(jobId);
      track(workspaceId,
          MoreMaps.merge(
              jobMetadata,
              jobAttemptMetadata,
              sourceDefMetadata,
              destinationDefMetadata,
              syncMetadata,
              stateMetadata,
              syncConfigMetadata));
    });
  }

  private Map<String, Object> generateSyncConfigMetadata(final JobConfig config,
                                                         final JsonNode sourceConfigSchema,
                                                         final JsonNode destinationConfigSchema) {
    if (config.getConfigType() == ConfigType.SYNC) {
      final JsonNode sourceConfiguration = config.getSync().getSourceConfiguration();
      final JsonNode destinationConfiguration = config.getSync().getDestinationConfiguration();

      final Map<String, Object> sourceMetadata = configToMetadata(CONFIG + ".source", sourceConfiguration, sourceConfigSchema);
      final Map<String, Object> destinationMetadata = configToMetadata(CONFIG + ".destination", destinationConfiguration, destinationConfigSchema);
      final Map<String, Object> catalogMetadata = getCatalogMetadata(config.getSync().getConfiguredAirbyteCatalog());

      return MoreMaps.merge(sourceMetadata, destinationMetadata, catalogMetadata);
    } else {
      return emptyMap();
    }
  }

  private Map<String, Object> getCatalogMetadata(final ConfiguredAirbyteCatalog catalog) {
    final Map<String, Object> output = new HashMap<>();

    for (final ConfiguredAirbyteStream stream : catalog.getStreams()) {
      output.put(CATALOG + ".sync_mode." + stream.getSyncMode().name().toLowerCase(), SET);
      output.put(CATALOG + ".destination_sync_mode." + stream.getDestinationSyncMode().name().toLowerCase(), SET);
    }

    return output;
  }

  /**
   * Does not support anyOf/allOf. Those aren't really used in config schemas anyway.
   *
   * @param jsonPath A prefix to add to all the keys in the returned map
   * @param schema   The JSON schema that {@code config} conforms to
   */
  protected static Map<String, Object> configToMetadata(final String jsonPath, final JsonNode config, final JsonNode schema) {
    final Map<String, Object> metadata = configToMetadata(config, schema);
    // Prepend all the keys with the root jsonPath
    // But leave the values unchanged
    return metadata.entrySet().stream().collect(toMap(
        e -> {
          final String key = e.getKey();
          if (key != null) {
            return jsonPath + "." + key;
          } else {
            return jsonPath;
          }
        },
        Entry::getValue
    ));
  }

  private static Map<String, Object> configToMetadata(final JsonNode config, final JsonNode schema) {
    final Map<String, Object> output = new HashMap<>();

    final JsonNode maybeOneOfSchemas = schema.get("oneOf");
    if (schema.hasNonNull("const")) {
      // If this schema is a const, then just dump it into a map:
      // * If it's an object, flatten it
      // * Otherwise, do some basic conversions to value-ish data.
      if (config.isObject()) {
        output.putAll(flatten(config));
      } else if (config.isBoolean()) {
        output.put(null, config.asBoolean());
      } else if (config.isLong()) {
        output.put(null, config.asLong());
      } else if (config.isDouble()) {
        output.put(null, config.asDouble());
      } else if (config.isValueNode() && !config.isNull()) {
        output.put(null, config.asText());
      } else {
        // Fallback handling for e.g. arrays
        output.put(null, config.toString());
      }
    } else if (maybeOneOfSchemas != null && maybeOneOfSchemas.isArray()) {
      // If this schema is a oneOf, then find the first sub-schema which the config matches
      // and use that sub-schema to convert the config to a map
      final JsonSchemaValidator validator = new JsonSchemaValidator();
      for (final Iterator<JsonNode> it = maybeOneOfSchemas.elements(); it.hasNext(); ) {
        final JsonNode subSchema = it.next();
        if (validator.test(subSchema, config)) {
          return configToMetadata(config, subSchema);
        }
      }
      // If we didn't match any of the subschemas, then something is wrong. Bail out silently.
      return emptyMap();
    } else if (config.isObject()) {
      // If the schema is not a oneOf, but the config is an object (i.e. the schema has "type": "object")
      // then we need to recursively convert each field of the object to a map.
      // Note: this doesn't handle additionalProperties, so if a config contains properties that are not explicitly declared in the schema, they will be ignored.
      final JsonNode maybeProperties = schema.get("properties");
      for (final Iterator<Entry<String, JsonNode>> it = config.fields(); it.hasNext(); ) {
        final Entry<String, JsonNode> entry = it.next();
        final String field = entry.getKey();
        final JsonNode value = entry.getValue();
        if (maybeProperties != null && maybeProperties.hasNonNull(field)) {
          output.putAll(configToMetadata(field, value, maybeProperties.get(field)));
        }
      }
    } else if (config.isBoolean()) {
      // TODO handle this in the else branch?
      output.put(null, config.asBoolean());
    } else if ((!config.isTextual() && !config.isNull()) || (config.isTextual() && !config.asText().isEmpty())) {
      // This is either non-textual (i.e. array) or non-empty text
      // TODO Why do we ignore non-text values (integer/number)?
      output.put(null, SET);
    }
    // Otherwise, this is an empty string, so just ignore it

    return output;
  }

  private static Map<String, Object> flatten(final JsonNode node) {
    final Map<String, Object> output = new HashMap<>();
    for (final Iterator<Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
      final Entry<String, JsonNode> entry = it.next();
      final String field = entry.getKey();
      final JsonNode value = entry.getValue();
      if (value.isObject()) {
        output.putAll(flatten(entry.getValue()).entrySet().stream().collect(toMap(
            e -> {
              final String key = e.getKey();
              if (key != null) {
                return field + "." + key;
              } else {
                return field;
              }
            },
            Entry::getValue
        )));
      } else {
        if (value.isTextual()) {
          output.put(field, value.asText());
        } else {
          output.put(field, value.toString());
        }
      }
    }
    return output;
  }

  private Map<String, Object> generateSyncMetadata(final UUID connectionId) throws ConfigNotFoundException, IOException, JsonValidationException {
    final Map<String, Object> operationUsage = new HashMap<>();
    final StandardSync standardSync = configRepository.getStandardSync(connectionId);
    for (final UUID operationId : standardSync.getOperationIds()) {
      final StandardSyncOperation operation = configRepository.getStandardSyncOperation(operationId);
      if (operation != null) {
        final Integer usageCount = (Integer) operationUsage.getOrDefault(OPERATION + operation.getOperatorType(), 0);
        operationUsage.put(OPERATION + operation.getOperatorType(), usageCount + 1);
      }
    }
    return MoreMaps.merge(TrackingMetadata.generateSyncMetadata(standardSync), operationUsage);
  }

  private static ImmutableMap<String, Object> generateStateMetadata(final JobState jobState) {
    final Builder<String, Object> metadata = ImmutableMap.builder();

    switch (jobState) {
      case STARTED -> {
        metadata.put("attempt_stage", "STARTED");
      }
      case SUCCEEDED, FAILED -> {
        metadata.put("attempt_stage", "ENDED");
        metadata.put("attempt_completion_status", jobState);
      }
    }

    return metadata.build();
  }

  /**
   * The CheckConnection jobs (both source and destination) of the
   * {@link io.airbyte.scheduler.client.SynchronousSchedulerClient} interface can have a successful
   * job with a failed check. Because of this, tracking just the job attempt status does not capture
   * the whole picture. The `check_connection_outcome` field tracks this.
   */
  private ImmutableMap<String, Object> generateCheckConnectionMetadata(final StandardCheckConnectionOutput output) {
    if (output == null) {
      return ImmutableMap.of();
    }
    final Builder<String, Object> metadata = ImmutableMap.builder();
    metadata.put("check_connection_outcome", output.getStatus().toString());
    return metadata.build();
  }

  private ImmutableMap<String, Object> generateDestinationDefinitionMetadata(final UUID destinationDefinitionId)
      throws ConfigNotFoundException, IOException, JsonValidationException {
    final StandardDestinationDefinition destinationDefinition = configRepository.getStandardDestinationDefinition(destinationDefinitionId);
    return TrackingMetadata.generateDestinationDefinitionMetadata(destinationDefinition);
  }

  private ImmutableMap<String, Object> generateSourceDefinitionMetadata(final UUID sourceDefinitionId)
      throws ConfigNotFoundException, IOException, JsonValidationException {
    final StandardSourceDefinition sourceDefinition = configRepository.getStandardSourceDefinition(sourceDefinitionId);
    return TrackingMetadata.generateSourceDefinitionMetadata(sourceDefinition);
  }

  private ImmutableMap<String, Object> generateJobMetadata(final String jobId, final ConfigType configType) {
    return generateJobMetadata(jobId, configType, 0);
  }

  private ImmutableMap<String, Object> generateJobMetadata(final String jobId, final ConfigType configType, final int attempt) {
    final Builder<String, Object> metadata = ImmutableMap.builder();
    metadata.put("job_type", configType);
    metadata.put("job_id", jobId);
    metadata.put("attempt_id", attempt);

    return metadata.build();
  }

  private ImmutableMap<String, Object> generateJobAttemptMetadata(final long jobId, final JobState jobState) throws IOException {
    final Job job = jobPersistence.getJob(jobId);
    if (jobState != JobState.STARTED) {
      return TrackingMetadata.generateJobAttemptMetadata(job);
    } else {
      return ImmutableMap.of();
    }
  }

  private void track(final UUID workspaceId, final Map<String, Object> metadata)
      throws JsonValidationException, ConfigNotFoundException, IOException {
    // unfortunate but in the case of jobs that cannot be linked to a workspace there not a sensible way
    // track it.
    if (workspaceId != null) {
      final StandardWorkspace standardWorkspace = configRepository.getStandardWorkspace(workspaceId, true);
      if (standardWorkspace != null && standardWorkspace.getName() != null) {
        final Map<String, Object> standardTrackingMetadata = ImmutableMap.of(
            "workspace_id", workspaceId,
            "workspace_name", standardWorkspace.getName());

        trackingClient.track(workspaceId, MESSAGE_NAME, MoreMaps.merge(metadata, standardTrackingMetadata));
      }
    }
  }

}
