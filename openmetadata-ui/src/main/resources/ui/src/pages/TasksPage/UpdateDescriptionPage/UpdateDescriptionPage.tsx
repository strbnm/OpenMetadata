/*
 *  Copyright 2022 Collate.
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

import { Button, Form, FormProps, Input, Space, Typography } from 'antd';
import { useForm } from 'antd/lib/form/Form';
import { AxiosError } from 'axios';
import { ActivityFeedTabs } from 'components/ActivityFeed/ActivityFeedTab/ActivityFeedTab.interface';
import ResizablePanels from 'components/common/ResizablePanels/ResizablePanels';
import TitleBreadcrumb from 'components/common/title-breadcrumb/title-breadcrumb.component';
import ExploreSearchCard from 'components/ExploreV1/ExploreSearchCard/ExploreSearchCard';
import { SearchedDataProps } from 'components/searched-data/SearchedData.interface';
import { isEmpty, isUndefined } from 'lodash';
import React, { useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useHistory, useLocation, useParams } from 'react-router-dom';
import { postThread } from 'rest/feedsAPI';
import { getEntityDetailLink } from 'utils/CommonUtils';
import AppState from '../../../AppState';
import { FQN_SEPARATOR_CHAR } from '../../../constants/char.constants';
import { EntityField } from '../../../constants/Feeds.constants';
import { EntityTabs, EntityType } from '../../../enums/entity.enum';
import {
  CreateThread,
  TaskType,
  ThreadType,
} from '../../../generated/api/feed/createThread';
import {
  ENTITY_LINK_SEPARATOR,
  getEntityFeedLink,
  getEntityName,
} from '../../../utils/EntityUtils';
import {
  fetchEntityDetail,
  fetchOptions,
  getBreadCrumbList,
  getColumnObject,
  getEntityColumnsDetails,
} from '../../../utils/TasksUtils';
import { showErrorToast, showSuccessToast } from '../../../utils/ToastUtils';
import Assignees from '../shared/Assignees';
import { DescriptionTabs } from '../shared/DescriptionTabs';
import '../TaskPage.style.less';
import { EntityData, Option } from '../TasksPage.interface';

const UpdateDescription = () => {
  const { t } = useTranslation();
  const location = useLocation();
  const history = useHistory();
  const [form] = useForm();

  const { entityType, entityFQN } = useParams<{ [key: string]: string }>();
  const queryParams = new URLSearchParams(location.search);

  const field = queryParams.get('field');
  const value = queryParams.get('value');

  const [entityData, setEntityData] = useState<EntityData>({} as EntityData);
  const [options, setOptions] = useState<Option[]>([]);
  const [assignees, setAssignees] = useState<Array<Option>>([]);
  const [currentDescription, setCurrentDescription] = useState<string>('');

  const getSanitizeValue = value?.replaceAll(/^"|"$/g, '') || '';

  // get current user details
  const currentUser = useMemo(
    () => AppState.getCurrentUserDetails(),
    [AppState.userDetails, AppState.nonSecureUserDetails]
  );

  const message = `Update description for ${getSanitizeValue || entityType} ${
    field !== EntityField.COLUMNS ? getEntityName(entityData) : ''
  }`;

  const back = () => history.goBack();

  const columnObject = useMemo(() => {
    const column = getSanitizeValue.split(FQN_SEPARATOR_CHAR).slice(-1);

    return getColumnObject(
      column[0],
      getEntityColumnsDetails(entityType, entityData),
      entityType as EntityType
    );
  }, [field, entityData, entityType]);

  const getDescription = () => {
    if (!isEmpty(columnObject) && !isUndefined(columnObject)) {
      return columnObject.description || '';
    } else {
      return entityData.description || '';
    }
  };

  const onSearch = (query: string) => {
    fetchOptions(query, setOptions);
  };

  const getTaskAbout = () => {
    if (field && value) {
      return `${field}${ENTITY_LINK_SEPARATOR}${value}${ENTITY_LINK_SEPARATOR}description`;
    } else {
      return EntityField.DESCRIPTION;
    }
  };

  const onCreateTask: FormProps['onFinish'] = (value) => {
    const data: CreateThread = {
      from: currentUser?.name as string,
      message: value.title || message,
      about: getEntityFeedLink(entityType, entityFQN, getTaskAbout()),
      taskDetails: {
        assignees: assignees.map((assignee) => ({
          id: assignee.value,
          type: assignee.type,
        })),
        suggestion: value.description,
        type: TaskType.UpdateDescription,
        oldValue: currentDescription,
      },
      type: ThreadType.Task,
    };
    postThread(data)
      .then(() => {
        showSuccessToast(
          t('server.create-entity-success', {
            entity: t('label.task'),
          })
        );
        history.push(
          getEntityDetailLink(
            entityType as EntityType,
            entityFQN,
            EntityTabs.ACTIVITY_FEED,
            ActivityFeedTabs.TASKS
          )
        );
      })
      .catch((err: AxiosError) => showErrorToast(err));
  };

  useEffect(() => {
    fetchEntityDetail(
      entityType as EntityType,
      entityFQN as string,
      setEntityData
    );
  }, [entityFQN, entityType]);

  useEffect(() => {
    const owner = entityData.owner;
    let defaultAssignee: Option[] = [];
    if (owner) {
      defaultAssignee = [
        {
          label: getEntityName(owner),
          value: owner.id || '',
          type: owner.type,
        },
      ];
      setAssignees(defaultAssignee);
      setOptions(defaultAssignee);
    }
    form.setFieldsValue({
      title: message.trimEnd(),
      assignees: defaultAssignee,
      description: getDescription(),
    });
  }, [entityData]);

  useEffect(() => {
    setCurrentDescription(getDescription());
  }, [entityData, columnObject]);

  return (
    <ResizablePanels
      firstPanel={{
        minWidth: 700,
        flex: 0.6,
        children: (
          <div className="max-width-md w-9/10 m-x-auto m-y-md d-grid gap-4">
            <TitleBreadcrumb
              titleLinks={[
                ...getBreadCrumbList(entityData, entityType as EntityType),
                {
                  name: t('label.create-entity', {
                    entity: t('label.task'),
                  }),
                  activeTitle: true,
                  url: '',
                },
              ]}
            />

            <div className="m-t-0 request-description" key="update-description">
              <Typography.Paragraph
                className="text-base"
                data-testid="form-title">
                {t('label.create-entity', {
                  entity: t('label.task'),
                })}
              </Typography.Paragraph>
              <Form form={form} layout="vertical" onFinish={onCreateTask}>
                <Form.Item
                  data-testid="title"
                  label={`${t('label.title')}:`}
                  name="title">
                  <Input
                    disabled
                    placeholder={t('label.task-entity', {
                      entity: t('label.title'),
                    })}
                  />
                </Form.Item>
                <Form.Item
                  data-testid="assignees"
                  label={`${t('label.assignee-plural')}:`}
                  name="assignees"
                  rules={[
                    {
                      required: true,
                      message: t('message.field-text-is-required', {
                        fieldText: t('label.assignee-plural'),
                      }),
                    },
                  ]}>
                  <Assignees
                    options={options}
                    value={assignees}
                    onChange={setAssignees}
                    onSearch={onSearch}
                  />
                </Form.Item>

                {currentDescription && (
                  <Form.Item
                    data-testid="description-tabs"
                    label={`${t('label.description')}:`}
                    name="description"
                    rules={[
                      {
                        required: true,
                        message: t('message.field-text-is-required', {
                          fieldText: t('label.description'),
                        }),
                      },
                    ]}>
                    <DescriptionTabs
                      suggestion={currentDescription}
                      value={currentDescription}
                    />
                  </Form.Item>
                )}

                <Form.Item>
                  <Space
                    className="w-full justify-end"
                    data-testid="cta-buttons"
                    size={16}>
                    <Button type="link" onClick={back}>
                      {t('label.back')}
                    </Button>
                    <Button htmlType="submit" type="primary">
                      {t('label.submit')}
                    </Button>
                  </Space>
                </Form.Item>
              </Form>
            </div>
          </div>
        ),
      }}
      pageTitle={t('label.task')}
      secondPanel={{
        minWidth: 60,
        flex: 0.4,
        children: (
          <ExploreSearchCard
            hideBreadcrumbs
            showTags
            id={entityData.id ?? ''}
            source={
              {
                ...entityData,
                entityType,
              } as SearchedDataProps['data'][number]['_source']
            }
          />
        ),
      }}
    />
  );
};

export default UpdateDescription;
