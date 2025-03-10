/*
 *  Copyright 2021 Collate
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.openmetadata.service.jdbi3;

import static org.openmetadata.common.utils.CommonUtil.listOrEmpty;
import static org.openmetadata.common.utils.CommonUtil.nullOrEmpty;
import static org.openmetadata.schema.type.Include.ALL;
import static org.openmetadata.service.Entity.FIELD_DESCRIPTION;
import static org.openmetadata.service.Entity.FIELD_DISPLAY_NAME;
import static org.openmetadata.service.Entity.FIELD_TAGS;
import static org.openmetadata.service.Entity.MESSAGING_SERVICE;
import static org.openmetadata.service.util.EntityUtil.getSchemaField;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.json.JsonPatch;
import org.jdbi.v3.sqlobject.transaction.Transaction;
import org.openmetadata.schema.EntityInterface;
import org.openmetadata.schema.entity.data.Topic;
import org.openmetadata.schema.entity.services.MessagingService;
import org.openmetadata.schema.type.EntityReference;
import org.openmetadata.schema.type.Field;
import org.openmetadata.schema.type.Relationship;
import org.openmetadata.schema.type.TagLabel;
import org.openmetadata.schema.type.TaskDetails;
import org.openmetadata.schema.type.topic.CleanupPolicy;
import org.openmetadata.schema.type.topic.TopicSampleData;
import org.openmetadata.service.Entity;
import org.openmetadata.service.exception.CatalogExceptionMessage;
import org.openmetadata.service.jdbi3.EntityRepository.EntityUpdater;
import org.openmetadata.service.resources.feeds.MessageParser;
import org.openmetadata.service.resources.topics.TopicResource;
import org.openmetadata.service.security.mask.PIIMasker;
import org.openmetadata.service.util.EntityUtil;
import org.openmetadata.service.util.EntityUtil.Fields;
import org.openmetadata.service.util.FullyQualifiedName;
import org.openmetadata.service.util.JsonUtils;

public class TopicRepository extends EntityRepository<Topic> {
  @Override
  public void setFullyQualifiedName(Topic topic) {
    topic.setFullyQualifiedName(FullyQualifiedName.add(topic.getService().getFullyQualifiedName(), topic.getName()));
    if (topic.getMessageSchema() != null) {
      setFieldFQN(topic.getFullyQualifiedName(), topic.getMessageSchema().getSchemaFields());
    }
  }

  public TopicRepository(CollectionDAO dao) {
    super(TopicResource.COLLECTION_PATH, Entity.TOPIC, Topic.class, dao.topicDAO(), dao, "", "");
  }

  @Override
  public void prepare(Topic topic) {
    MessagingService messagingService = Entity.getEntity(topic.getService(), "", ALL);
    topic.setService(messagingService.getEntityReference());
    topic.setServiceType(messagingService.getServiceType());
    // Validate field tags
    if (topic.getMessageSchema() != null) {
      addDerivedFieldTags(topic.getMessageSchema().getSchemaFields());
      validateSchemaFieldTags(topic.getMessageSchema().getSchemaFields());
    }
  }

  @Override
  public void storeEntity(Topic topic, boolean update) {
    // Relationships and fields such as service are derived and not stored as part of json
    EntityReference service = topic.getService();
    topic.withService(null);

    // Don't store fields tags as JSON but build it on the fly based on relationships
    List<Field> fieldsWithTags = null;
    if (topic.getMessageSchema() != null) {
      fieldsWithTags = topic.getMessageSchema().getSchemaFields();
      topic.getMessageSchema().setSchemaFields(cloneWithoutTags(fieldsWithTags));
      topic.getMessageSchema().getSchemaFields().forEach(field -> field.setTags(null));
    }

    store(topic, update);

    // Restore the relationships
    if (fieldsWithTags != null) {
      topic.getMessageSchema().withSchemaFields(fieldsWithTags);
    }
    topic.withService(service);
  }

  @Override
  public void storeRelationships(Topic topic) {
    setService(topic, topic.getService());
  }

  @Override
  public Topic setInheritedFields(Topic topic, Fields fields) {
    // If topic does not have domain, then inherit it from parent messaging service
    MessagingService service = Entity.getEntity(MESSAGING_SERVICE, topic.getService().getId(), "domain", ALL);
    return inheritDomain(topic, fields, service);
  }

  @Override
  public Topic setFields(Topic topic, Fields fields) {
    topic.setService(getContainer(topic.getId()));
    if (topic.getMessageSchema() != null) {
      getFieldTags(fields.contains(FIELD_TAGS), topic.getMessageSchema().getSchemaFields());
    }
    return topic;
  }

  @Override
  public Topic clearFields(Topic topic, Fields fields) {
    return topic;
  }

  @Override
  public TopicUpdater getUpdater(Topic original, Topic updated, Operation operation) {
    return new TopicUpdater(original, updated, operation);
  }

  public void setService(Topic topic, EntityReference service) {
    if (service != null && topic != null) {
      addRelationship(service.getId(), topic.getId(), service.getType(), Entity.TOPIC, Relationship.CONTAINS);
      topic.setService(service);
    }
  }

  public Topic getSampleData(UUID topicId, boolean authorizePII) {
    // Validate the request content
    Topic topic = dao.findEntityById(topicId);

    TopicSampleData sampleData =
        JsonUtils.readValue(
            daoCollection.entityExtensionDAO().getExtension(topic.getId().toString(), "topic.sampleData"),
            TopicSampleData.class);
    topic.setSampleData(sampleData);
    setFieldsInternal(topic, Fields.EMPTY_FIELDS);

    // Set the fields tags. Will be used to mask the sample data
    if (!authorizePII) {
      getFieldTags(true, topic.getMessageSchema().getSchemaFields());
      topic.setTags(getTags(topic));
      return PIIMasker.getSampleData(topic);
    }

    return topic;
  }

  @Transaction
  public Topic addSampleData(UUID topicId, TopicSampleData sampleData) {
    // Validate the request content
    Topic topic = daoCollection.topicDAO().findEntityById(topicId);

    daoCollection
        .entityExtensionDAO()
        .insert(topicId.toString(), "topic.sampleData", "topicSampleData", JsonUtils.pojoToJson(sampleData));
    setFieldsInternal(topic, Fields.EMPTY_FIELDS);
    return topic.withSampleData(sampleData);
  }

  private void setFieldFQN(String parentFQN, List<Field> fields) {
    fields.forEach(
        c -> {
          String fieldFqn = FullyQualifiedName.add(parentFQN, c.getName());
          c.setFullyQualifiedName(fieldFqn);
          if (c.getChildren() != null) {
            setFieldFQN(fieldFqn, c.getChildren());
          }
        });
  }

  private void getFieldTags(boolean setTags, List<Field> fields) {
    for (Field f : listOrEmpty(fields)) {
      if (f.getTags() == null) {
        f.setTags(setTags ? getTags(f.getFullyQualifiedName()) : null);
        getFieldTags(setTags, f.getChildren());
      }
    }
  }

  private void addDerivedFieldTags(List<Field> fields) {
    if (nullOrEmpty(fields)) {
      return;
    }

    for (Field field : fields) {
      field.setTags(addDerivedTags(field.getTags()));
      if (field.getChildren() != null) {
        addDerivedFieldTags(field.getChildren());
      }
    }
  }

  List<Field> cloneWithoutTags(List<Field> fields) {
    if (nullOrEmpty(fields)) {
      return fields;
    }
    List<Field> copy = new ArrayList<>();
    fields.forEach(f -> copy.add(cloneWithoutTags(f)));
    return copy;
  }

  private Field cloneWithoutTags(Field field) {
    List<Field> children = cloneWithoutTags(field.getChildren());
    return new Field()
        .withDescription(field.getDescription())
        .withName(field.getName())
        .withDisplayName(field.getDisplayName())
        .withFullyQualifiedName(field.getFullyQualifiedName())
        .withDataType(field.getDataType())
        .withDataTypeDisplay(field.getDataTypeDisplay())
        .withChildren(children);
  }

  private void validateSchemaFieldTags(List<Field> fields) {
    // Add field level tags by adding tag to field relationship
    for (Field field : fields) {
      checkMutuallyExclusive(field.getTags());
      if (field.getChildren() != null) {
        validateSchemaFieldTags(field.getChildren());
      }
    }
  }

  private void applyTags(List<Field> fields) {
    // Add field level tags by adding tag to field relationship
    for (Field field : fields) {
      applyTags(field.getTags(), field.getFullyQualifiedName());
      if (field.getChildren() != null) {
        applyTags(field.getChildren());
      }
    }
  }

  @Override
  public void applyTags(Topic topic) {
    // Add table level tags by adding tag to table relationship
    super.applyTags(topic);
    if (topic.getMessageSchema() != null) {
      applyTags(topic.getMessageSchema().getSchemaFields());
    }
  }

  @Override
  public List<TagLabel> getAllTags(EntityInterface entity) {
    List<TagLabel> allTags = new ArrayList<>();
    Topic topic = (Topic) entity;
    EntityUtil.mergeTags(allTags, topic.getTags());
    List<Field> schemaFields = topic.getMessageSchema() != null ? topic.getMessageSchema().getSchemaFields() : null;
    for (Field schemaField : listOrEmpty(schemaFields)) {
      EntityUtil.mergeTags(allTags, schemaField.getTags());
    }
    return allTags;
  }

  @Override
  public void update(TaskDetails task, MessageParser.EntityLink entityLink, String newValue, String user) {
    if (entityLink.getFieldName().equals("messageSchema")) {
      String schemaName = entityLink.getArrayFieldName();
      String childrenSchemaName = "";
      if (entityLink.getArrayFieldName().contains(".")) {
        String fieldNameWithoutQuotes =
            entityLink.getArrayFieldName().substring(1, entityLink.getArrayFieldName().length() - 1);
        schemaName = fieldNameWithoutQuotes.substring(0, fieldNameWithoutQuotes.indexOf("."));
        childrenSchemaName = fieldNameWithoutQuotes.substring(fieldNameWithoutQuotes.lastIndexOf(".") + 1);
      }
      Topic topic = getByName(null, entityLink.getEntityFQN(), getFields("tags"), ALL, false);
      Field schemaField = null;
      for (Field field : topic.getMessageSchema().getSchemaFields()) {
        if (field.getName().equals(schemaName)) {
          schemaField = field;
          break;
        }
      }
      if (!"".equals(childrenSchemaName) && schemaField != null) {
        schemaField = getchildrenSchemaField(schemaField.getChildren(), childrenSchemaName);
      }
      if (schemaField == null) {
        throw new IllegalArgumentException(
            CatalogExceptionMessage.invalidFieldName("schema", entityLink.getArrayFieldName()));
      }

      String origJson = JsonUtils.pojoToJson(topic);
      if (EntityUtil.isDescriptionTask(task.getType())) {
        schemaField.setDescription(newValue);
      } else if (EntityUtil.isTagTask(task.getType())) {
        List<TagLabel> tags = JsonUtils.readObjects(newValue, TagLabel.class);
        schemaField.setTags(tags);
      }
      String updatedEntityJson = JsonUtils.pojoToJson(topic);
      JsonPatch patch = JsonUtils.getJsonPatch(origJson, updatedEntityJson);
      patch(null, topic.getId(), user, patch);
      return;
    }
    super.update(task, entityLink, newValue, user);
  }

  private static Field getchildrenSchemaField(List<Field> fields, String childrenSchemaName) {
    Field childrenSchemaField = null;
    for (Field field : fields) {
      if (field.getName().equals(childrenSchemaName)) {
        childrenSchemaField = field;
        break;
      }
    }
    if (childrenSchemaField == null) {
      for (Field field : fields) {
        if (field.getChildren() != null) {
          childrenSchemaField = getchildrenSchemaField(field.getChildren(), childrenSchemaName);
          if (childrenSchemaField != null) {
            break;
          }
        }
      }
    }
    return childrenSchemaField;
  }

  public static Set<TagLabel> getAllFieldTags(Field field) {
    Set<TagLabel> tags = new HashSet<>();
    if (!listOrEmpty(field.getTags()).isEmpty()) {
      tags.addAll(field.getTags());
    }
    for (Field c : listOrEmpty(field.getChildren())) {
      tags.addAll(getAllFieldTags(c));
    }
    return tags;
  }

  public class TopicUpdater extends EntityUpdater {
    public static final String FIELD_DATA_TYPE_DISPLAY = "dataTypeDisplay";

    public TopicUpdater(Topic original, Topic updated, Operation operation) {
      super(original, updated, operation);
    }

    @Override
    public void entitySpecificUpdate() {
      recordChange("maximumMessageSize", original.getMaximumMessageSize(), updated.getMaximumMessageSize());
      recordChange("minimumInSyncReplicas", original.getMinimumInSyncReplicas(), updated.getMinimumInSyncReplicas());
      recordChange("partitions", original.getPartitions(), updated.getPartitions());
      recordChange("replicationFactor", original.getReplicationFactor(), updated.getReplicationFactor());
      recordChange("retentionTime", original.getRetentionTime(), updated.getRetentionTime());
      recordChange("retentionSize", original.getRetentionSize(), updated.getRetentionSize());
      if (updated.getMessageSchema() != null) {
        recordChange(
            "messageSchema.schemaText",
            original.getMessageSchema() == null ? null : original.getMessageSchema().getSchemaText(),
            updated.getMessageSchema().getSchemaText());
        recordChange(
            "messageSchema.schemaType",
            original.getMessageSchema() == null ? null : original.getMessageSchema().getSchemaType(),
            updated.getMessageSchema().getSchemaType());
        updateSchemaFields(
            "messageSchema.schemaFields",
            original.getMessageSchema() == null ? null : original.getMessageSchema().getSchemaFields(),
            updated.getMessageSchema().getSchemaFields(),
            EntityUtil.schemaFieldMatch);
      }
      recordChange("topicConfig", original.getTopicConfig(), updated.getTopicConfig());
      updateCleanupPolicies(original, updated);
    }

    private void updateCleanupPolicies(Topic original, Topic updated) {
      List<CleanupPolicy> added = new ArrayList<>();
      List<CleanupPolicy> deleted = new ArrayList<>();
      recordListChange(
          "cleanupPolicies",
          original.getCleanupPolicies(),
          updated.getCleanupPolicies(),
          added,
          deleted,
          CleanupPolicy::equals);
    }

    private void updateSchemaFields(
        String fieldName, List<Field> origFields, List<Field> updatedFields, BiPredicate<Field, Field> fieldMatch) {
      List<Field> deletedFields = new ArrayList<>();
      List<Field> addedFields = new ArrayList<>();
      recordListChange(fieldName, origFields, updatedFields, addedFields, deletedFields, fieldMatch);
      // carry forward tags and description if deletedFields matches added field
      Map<String, Field> addedFieldMap =
          addedFields.stream().collect(Collectors.toMap(Field::getName, Function.identity()));

      for (Field deleted : deletedFields) {
        if (addedFieldMap.containsKey(deleted.getName())) {
          Field addedField = addedFieldMap.get(deleted.getName());
          if (nullOrEmpty(addedField.getDescription()) && nullOrEmpty(deleted.getDescription())) {
            addedField.setDescription(deleted.getDescription());
          }
          if (nullOrEmpty(addedField.getTags()) && nullOrEmpty(deleted.getTags())) {
            addedField.setTags(deleted.getTags());
          }
        }
      }

      // Delete tags related to deleted fields
      deletedFields.forEach(deleted -> daoCollection.tagUsageDAO().deleteTagsByTarget(deleted.getFullyQualifiedName()));

      // Add tags related to newly added fields
      for (Field added : addedFields) {
        applyTags(added.getTags(), added.getFullyQualifiedName());
      }

      // Carry forward the user generated metadata from existing fields to new fields
      for (Field updated : updatedFields) {
        // Find stored field matching name, data type and ordinal position
        Field stored = origFields.stream().filter(c -> fieldMatch.test(c, updated)).findAny().orElse(null);
        if (stored == null) { // New field added
          continue;
        }

        updateFieldDescription(stored, updated);
        updateFieldDataTypeDisplay(stored, updated);
        updateFieldDisplayName(stored, updated);
        updateTags(
            stored.getFullyQualifiedName(),
            EntityUtil.getFieldName(fieldName, updated.getName(), FIELD_TAGS),
            stored.getTags(),
            updated.getTags());

        if (updated.getChildren() != null && stored.getChildren() != null) {
          String childrenFieldName = EntityUtil.getFieldName(fieldName, updated.getName());
          updateSchemaFields(childrenFieldName, stored.getChildren(), updated.getChildren(), fieldMatch);
        }
      }

      majorVersionChange = majorVersionChange || !deletedFields.isEmpty();
    }

    private void updateFieldDescription(Field origField, Field updatedField) {
      if (operation.isPut() && !nullOrEmpty(origField.getDescription()) && updatedByBot()) {
        // Revert the non-empty field description if being updated by a bot
        updatedField.setDescription(origField.getDescription());
        return;
      }
      String field = getSchemaField(original, origField, FIELD_DESCRIPTION);
      recordChange(field, origField.getDescription(), updatedField.getDescription());
    }

    private void updateFieldDisplayName(Field origField, Field updatedField) {
      if (operation.isPut() && !nullOrEmpty(origField.getDescription()) && updatedByBot()) {
        // Revert the non-empty field description if being updated by a bot
        updatedField.setDisplayName(origField.getDisplayName());
        return;
      }
      String field = getSchemaField(original, origField, FIELD_DISPLAY_NAME);
      recordChange(field, origField.getDisplayName(), updatedField.getDisplayName());
    }

    private void updateFieldDataTypeDisplay(Field origField, Field updatedField) {
      if (operation.isPut() && !nullOrEmpty(origField.getDataTypeDisplay()) && updatedByBot()) {
        // Revert the non-empty field dataTypeDisplay if being updated by a bot
        updatedField.setDataTypeDisplay(origField.getDataTypeDisplay());
        return;
      }
      String field = getSchemaField(original, origField, FIELD_DATA_TYPE_DISPLAY);
      recordChange(field, origField.getDataTypeDisplay(), updatedField.getDataTypeDisplay());
    }
  }
}
