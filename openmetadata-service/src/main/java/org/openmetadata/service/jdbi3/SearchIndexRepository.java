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
import static org.openmetadata.service.Entity.FIELD_FOLLOWERS;
import static org.openmetadata.service.Entity.FIELD_TAGS;
import static org.openmetadata.service.Entity.SEARCH_SERVICE;
import static org.openmetadata.service.util.EntityUtil.getSearchIndexField;

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
import org.openmetadata.schema.entity.data.SearchIndex;
import org.openmetadata.schema.entity.services.SearchService;
import org.openmetadata.schema.type.EntityReference;
import org.openmetadata.schema.type.Relationship;
import org.openmetadata.schema.type.SearchIndexField;
import org.openmetadata.schema.type.TagLabel;
import org.openmetadata.schema.type.TaskDetails;
import org.openmetadata.schema.type.searchindex.SearchIndexSampleData;
import org.openmetadata.service.Entity;
import org.openmetadata.service.exception.CatalogExceptionMessage;
import org.openmetadata.service.resources.feeds.MessageParser;
import org.openmetadata.service.resources.searchindex.SearchIndexResource;
import org.openmetadata.service.security.mask.PIIMasker;
import org.openmetadata.service.util.EntityUtil;
import org.openmetadata.service.util.EntityUtil.Fields;
import org.openmetadata.service.util.FullyQualifiedName;
import org.openmetadata.service.util.JsonUtils;

public class SearchIndexRepository extends EntityRepository<SearchIndex> {
  @Override
  public void setFullyQualifiedName(SearchIndex searchIndex) {
    searchIndex.setFullyQualifiedName(
        FullyQualifiedName.add(searchIndex.getService().getFullyQualifiedName(), searchIndex.getName()));
    if (searchIndex.getFields() != null) {
      setFieldFQN(searchIndex.getFullyQualifiedName(), searchIndex.getFields());
    }
  }

  public SearchIndexRepository(CollectionDAO dao) {
    super(
        SearchIndexResource.COLLECTION_PATH, Entity.SEARCH_INDEX, SearchIndex.class, dao.searchIndexDAO(), dao, "", "");
  }

  @Override
  public void prepare(SearchIndex searchIndex) {
    SearchService searchService = Entity.getEntity(searchIndex.getService(), "", ALL);
    searchIndex.setService(searchService.getEntityReference());
    searchIndex.setServiceType(searchService.getServiceType());
    // Validate field tags
    if (searchIndex.getFields() != null) {
      addDerivedFieldTags(searchIndex.getFields());
      validateSchemaFieldTags(searchIndex.getFields());
    }
  }

  @Override
  public void storeEntity(SearchIndex searchIndex, boolean update) {
    // Relationships and fields such as service are derived and not stored as part of json
    EntityReference service = searchIndex.getService();
    searchIndex.withService(null);

    // Don't store fields tags as JSON but build it on the fly based on relationships
    List<SearchIndexField> fieldsWithTags = null;
    if (searchIndex.getFields() != null) {
      fieldsWithTags = searchIndex.getFields();
      searchIndex.setFields(cloneWithoutTags(fieldsWithTags));
      searchIndex.getFields().forEach(field -> field.setTags(null));
    }

    store(searchIndex, update);

    // Restore the relationships
    if (fieldsWithTags != null) {
      searchIndex.setFields(fieldsWithTags);
    }
    searchIndex.withService(service);
  }

  @Override
  public void storeRelationships(SearchIndex searchIndex) {
    setService(searchIndex, searchIndex.getService());
  }

  @Override
  public SearchIndex setInheritedFields(SearchIndex searchIndex, Fields fields) {
    // If searchIndex does not have domain, then inherit it from parent messaging service
    SearchService service = Entity.getEntity(SEARCH_SERVICE, searchIndex.getService().getId(), "domain", ALL);
    return inheritDomain(searchIndex, fields, service);
  }

  @Override
  public SearchIndex setFields(SearchIndex searchIndex, Fields fields) {
    searchIndex.setService(getContainer(searchIndex.getId()));
    searchIndex.setFollowers(fields.contains(FIELD_FOLLOWERS) ? getFollowers(searchIndex) : null);
    if (searchIndex.getFields() != null) {
      getFieldTags(fields.contains(FIELD_TAGS), searchIndex.getFields());
    }
    return searchIndex;
  }

  @Override
  public SearchIndex clearFields(SearchIndex searchIndex, Fields fields) {
    return searchIndex;
  }

  @Override
  public SearchIndexUpdater getUpdater(SearchIndex original, SearchIndex updated, Operation operation) {
    return new SearchIndexUpdater(original, updated, operation);
  }

  public void setService(SearchIndex searchIndex, EntityReference service) {
    if (service != null && searchIndex != null) {
      addRelationship(
          service.getId(), searchIndex.getId(), service.getType(), Entity.SEARCH_INDEX, Relationship.CONTAINS);
      searchIndex.setService(service);
    }
  }

  public SearchIndex getSampleData(UUID searchIndexId, boolean authorizePII) {
    // Validate the request content
    SearchIndex searchIndex = dao.findEntityById(searchIndexId);

    SearchIndexSampleData sampleData =
        JsonUtils.readValue(
            daoCollection.entityExtensionDAO().getExtension(searchIndex.getId().toString(), "searchIndex.sampleData"),
            SearchIndexSampleData.class);
    searchIndex.setSampleData(sampleData);
    setFieldsInternal(searchIndex, Fields.EMPTY_FIELDS);

    // Set the fields tags. Will be used to mask the sample data
    if (!authorizePII) {
      getFieldTags(true, searchIndex.getFields());
      searchIndex.setTags(getTags(searchIndex.getFullyQualifiedName()));
      return PIIMasker.getSampleData(searchIndex);
    }

    return searchIndex;
  }

  @Transaction
  public SearchIndex addSampleData(UUID searchIndexId, SearchIndexSampleData sampleData) {
    // Validate the request content
    SearchIndex searchIndex = daoCollection.searchIndexDAO().findEntityById(searchIndexId);

    daoCollection
        .entityExtensionDAO()
        .insert(
            searchIndexId.toString(),
            "searchIndex.sampleData",
            "searchIndexSampleData",
            JsonUtils.pojoToJson(sampleData));
    setFieldsInternal(searchIndex, Fields.EMPTY_FIELDS);
    return searchIndex.withSampleData(sampleData);
  }

  private void setFieldFQN(String parentFQN, List<SearchIndexField> fields) {
    fields.forEach(
        c -> {
          String fieldFqn = FullyQualifiedName.add(parentFQN, c.getName());
          c.setFullyQualifiedName(fieldFqn);
          if (c.getChildren() != null) {
            setFieldFQN(fieldFqn, c.getChildren());
          }
        });
  }

  private void getFieldTags(boolean setTags, List<SearchIndexField> fields) {
    for (SearchIndexField f : listOrEmpty(fields)) {
      f.setTags(setTags ? getTags(f.getFullyQualifiedName()) : null);
      getFieldTags(setTags, f.getChildren());
    }
  }

  private void addDerivedFieldTags(List<SearchIndexField> fields) {
    if (nullOrEmpty(fields)) {
      return;
    }

    for (SearchIndexField field : fields) {
      field.setTags(addDerivedTags(field.getTags()));
      if (field.getChildren() != null) {
        addDerivedFieldTags(field.getChildren());
      }
    }
  }

  List<SearchIndexField> cloneWithoutTags(List<SearchIndexField> fields) {
    if (nullOrEmpty(fields)) {
      return fields;
    }
    List<SearchIndexField> copy = new ArrayList<>();
    fields.forEach(f -> copy.add(cloneWithoutTags(f)));
    return copy;
  }

  private SearchIndexField cloneWithoutTags(SearchIndexField field) {
    List<SearchIndexField> children = cloneWithoutTags(field.getChildren());
    return new SearchIndexField()
        .withDescription(field.getDescription())
        .withName(field.getName())
        .withDisplayName(field.getDisplayName())
        .withFullyQualifiedName(field.getFullyQualifiedName())
        .withDataType(field.getDataType())
        .withDataTypeDisplay(field.getDataTypeDisplay())
        .withChildren(children);
  }

  private void validateSchemaFieldTags(List<SearchIndexField> fields) {
    // Add field level tags by adding tag to field relationship
    for (SearchIndexField field : fields) {
      checkMutuallyExclusive(field.getTags());
      if (field.getChildren() != null) {
        validateSchemaFieldTags(field.getChildren());
      }
    }
  }

  private void applyTags(List<SearchIndexField> fields) {
    // Add field level tags by adding tag to field relationship
    for (SearchIndexField field : fields) {
      applyTags(field.getTags(), field.getFullyQualifiedName());
      if (field.getChildren() != null) {
        applyTags(field.getChildren());
      }
    }
  }

  @Override
  public void applyTags(SearchIndex searchIndex) {
    // Add table level tags by adding tag to table relationship
    super.applyTags(searchIndex);
    if (searchIndex.getFields() != null) {
      applyTags(searchIndex.getFields());
    }
  }

  @Override
  public List<TagLabel> getAllTags(EntityInterface entity) {
    List<TagLabel> allTags = new ArrayList<>();
    SearchIndex searchIndex = (SearchIndex) entity;
    EntityUtil.mergeTags(allTags, searchIndex.getTags());
    List<SearchIndexField> schemaFields = searchIndex.getFields() != null ? searchIndex.getFields() : null;
    for (SearchIndexField schemaField : listOrEmpty(schemaFields)) {
      EntityUtil.mergeTags(allTags, schemaField.getTags());
    }
    return allTags;
  }

  @Override
  public void update(TaskDetails task, MessageParser.EntityLink entityLink, String newValue, String user) {
    if (entityLink.getFieldName().equals("fields")) {
      String schemaName = entityLink.getArrayFieldName();
      String childrenSchemaName = "";
      if (entityLink.getArrayFieldName().contains(".")) {
        String fieldNameWithoutQuotes =
            entityLink.getArrayFieldName().substring(1, entityLink.getArrayFieldName().length() - 1);
        schemaName = fieldNameWithoutQuotes.substring(0, fieldNameWithoutQuotes.indexOf("."));
        childrenSchemaName = fieldNameWithoutQuotes.substring(fieldNameWithoutQuotes.lastIndexOf(".") + 1);
      }
      SearchIndex searchIndex = getByName(null, entityLink.getEntityFQN(), getFields("tags"), ALL, false);
      SearchIndexField schemaField = null;
      for (SearchIndexField field : searchIndex.getFields()) {
        if (field.getName().equals(schemaName)) {
          schemaField = field;
          break;
        }
      }
      if (!"".equals(childrenSchemaName) && schemaField != null) {
        schemaField = getChildrenSchemaField(schemaField.getChildren(), childrenSchemaName);
      }
      if (schemaField == null) {
        throw new IllegalArgumentException(
            CatalogExceptionMessage.invalidFieldName("schema", entityLink.getArrayFieldName()));
      }

      String origJson = JsonUtils.pojoToJson(searchIndex);
      if (EntityUtil.isDescriptionTask(task.getType())) {
        schemaField.setDescription(newValue);
      } else if (EntityUtil.isTagTask(task.getType())) {
        List<TagLabel> tags = JsonUtils.readObjects(newValue, TagLabel.class);
        schemaField.setTags(tags);
      }
      String updatedEntityJson = JsonUtils.pojoToJson(searchIndex);
      JsonPatch patch = JsonUtils.getJsonPatch(origJson, updatedEntityJson);
      patch(null, searchIndex.getId(), user, patch);
      return;
    }
    super.update(task, entityLink, newValue, user);
  }

  private static SearchIndexField getChildrenSchemaField(List<SearchIndexField> fields, String childrenSchemaName) {
    SearchIndexField childrenSchemaField = null;
    for (SearchIndexField field : fields) {
      if (field.getName().equals(childrenSchemaName)) {
        childrenSchemaField = field;
        break;
      }
    }
    if (childrenSchemaField == null) {
      for (SearchIndexField field : fields) {
        if (field.getChildren() != null) {
          childrenSchemaField = getChildrenSchemaField(field.getChildren(), childrenSchemaName);
          if (childrenSchemaField != null) {
            break;
          }
        }
      }
    }
    return childrenSchemaField;
  }

  public static Set<TagLabel> getAllFieldTags(SearchIndexField field) {
    Set<TagLabel> tags = new HashSet<>();
    if (!listOrEmpty(field.getTags()).isEmpty()) {
      tags.addAll(field.getTags());
    }
    for (SearchIndexField c : listOrEmpty(field.getChildren())) {
      tags.addAll(getAllFieldTags(c));
    }
    return tags;
  }

  public class SearchIndexUpdater extends EntityUpdater {
    public static final String FIELD_DATA_TYPE_DISPLAY = "dataTypeDisplay";

    public SearchIndexUpdater(SearchIndex original, SearchIndex updated, Operation operation) {
      super(original, updated, operation);
    }

    @Override
    public void entitySpecificUpdate() {
      if (updated.getFields() != null) {
        updateSearchIndexFields(
            "fields",
            original.getFields() == null ? null : original.getFields(),
            updated.getFields(),
            EntityUtil.searchIndexFieldMatch);
      }
      recordChange("searchIndexSettings", original.getSearchIndexSettings(), updated.getSearchIndexSettings());
    }

    private void updateSearchIndexFields(
        String fieldName,
        List<SearchIndexField> origFields,
        List<SearchIndexField> updatedFields,
        BiPredicate<SearchIndexField, SearchIndexField> fieldMatch) {
      List<SearchIndexField> deletedFields = new ArrayList<>();
      List<SearchIndexField> addedFields = new ArrayList<>();
      recordListChange(fieldName, origFields, updatedFields, addedFields, deletedFields, fieldMatch);
      // carry forward tags and description if deletedFields matches added field
      Map<String, SearchIndexField> addedFieldMap =
          addedFields.stream().collect(Collectors.toMap(SearchIndexField::getName, Function.identity()));

      for (SearchIndexField deleted : deletedFields) {
        if (addedFieldMap.containsKey(deleted.getName())) {
          SearchIndexField addedField = addedFieldMap.get(deleted.getName());
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
      for (SearchIndexField added : addedFields) {
        applyTags(added.getTags(), added.getFullyQualifiedName());
      }

      // Carry forward the user generated metadata from existing fields to new fields
      for (SearchIndexField updated : updatedFields) {
        // Find stored field matching name, data type and ordinal position
        SearchIndexField stored = origFields.stream().filter(c -> fieldMatch.test(c, updated)).findAny().orElse(null);
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
          updateSearchIndexFields(childrenFieldName, stored.getChildren(), updated.getChildren(), fieldMatch);
        }
      }
      majorVersionChange = majorVersionChange || !deletedFields.isEmpty();
    }

    private void updateFieldDescription(SearchIndexField origField, SearchIndexField updatedField) {
      if (operation.isPut() && !nullOrEmpty(origField.getDescription()) && updatedByBot()) {
        // Revert the non-empty field description if being updated by a bot
        updatedField.setDescription(origField.getDescription());
        return;
      }
      String field = getSearchIndexField(original, origField, FIELD_DESCRIPTION);
      recordChange(field, origField.getDescription(), updatedField.getDescription());
    }

    private void updateFieldDisplayName(SearchIndexField origField, SearchIndexField updatedField) {
      if (operation.isPut() && !nullOrEmpty(origField.getDescription()) && updatedByBot()) {
        // Revert the non-empty field description if being updated by a bot
        updatedField.setDisplayName(origField.getDisplayName());
        return;
      }
      String field = getSearchIndexField(original, origField, FIELD_DISPLAY_NAME);
      recordChange(field, origField.getDisplayName(), updatedField.getDisplayName());
    }

    private void updateFieldDataTypeDisplay(SearchIndexField origField, SearchIndexField updatedField) {
      if (operation.isPut() && !nullOrEmpty(origField.getDataTypeDisplay()) && updatedByBot()) {
        // Revert the non-empty field dataTypeDisplay if being updated by a bot
        updatedField.setDataTypeDisplay(origField.getDataTypeDisplay());
        return;
      }
      String field = getSearchIndexField(original, origField, FIELD_DATA_TYPE_DISPLAY);
      recordChange(field, origField.getDataTypeDisplay(), updatedField.getDataTypeDisplay());
    }
  }
}
