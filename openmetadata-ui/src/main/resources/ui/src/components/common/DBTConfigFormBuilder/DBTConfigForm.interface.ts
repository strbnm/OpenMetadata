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

import { FilterPatternEnum } from 'enums/filterPattern.enum';
import { FormSubmitType } from '../../../enums/form.enum';
import {
  Credentials,
  DBTConfigurationSource,
  GCPCredentialsValues,
} from '../../../generated/metadataIngestion/dbtPipeline';
import {
  AddIngestionState,
  ModifiedDBTConfigurationSource,
} from '../../AddIngestion/addIngestion.interface';
import { DBT_SOURCES, GCS_CONFIG } from './DBTFormEnum';

export interface DBTFormCommonProps {
  okText: string;
  cancelText: string;
  onCancel: () => void;
  onSubmit: (data?: DBTConfigurationSource) => void;
}

export interface DBTConfigFormProps extends DBTFormCommonProps {
  formType: FormSubmitType;
  data: AddIngestionState;
  onChange: (newState: Partial<AddIngestionState>) => void;
  onFocus: (fieldName: string) => void;
  getExcludeValue: (value: string[], type: FilterPatternEnum) => void;
  getIncludeValue: (value: string[], type: FilterPatternEnum) => void;
  handleShowFilter: (value: boolean, type: string) => void;
}

export type DbtConfigCloud = Pick<
  ModifiedDBTConfigurationSource,
  | 'dbtCloudAccountId'
  | 'dbtCloudAuthToken'
  | 'dbtUpdateDescriptions'
  | 'dbtCloudProjectId'
  | 'dbtClassificationName'
  | 'dbtCloudUrl'
  | 'dbtCloudJobId'
  | 'includeTags'
>;

export type DbtConfigLocal = Pick<
  ModifiedDBTConfigurationSource,
  | 'dbtCatalogFilePath'
  | 'dbtManifestFilePath'
  | 'dbtRunResultsFilePath'
  | 'dbtUpdateDescriptions'
  | 'dbtClassificationName'
  | 'includeTags'
>;

export type DbtConfigHttp = Pick<
  ModifiedDBTConfigurationSource,
  | 'dbtCatalogHttpPath'
  | 'dbtManifestHttpPath'
  | 'dbtRunResultsHttpPath'
  | 'dbtUpdateDescriptions'
  | 'dbtClassificationName'
  | 'includeTags'
>;

export type DbtConfigS3GCS = Pick<
  ModifiedDBTConfigurationSource,
  | 'dbtSecurityConfig'
  | 'dbtPrefixConfig'
  | 'dbtUpdateDescriptions'
  | 'dbtClassificationName'
  | 'includeTags'
>;

export type DbtConfigAzure = Pick<
  ModifiedDBTConfigurationSource,
  | 'dbtSecurityConfig'
  | 'dbtPrefixConfig'
  | 'dbtUpdateDescriptions'
  | 'dbtClassificationName'
  | 'includeTags'
>;

export type DbtS3Creds = Pick<
  Credentials,
  | 'awsAccessKeyId'
  | 'awsRegion'
  | 'awsSecretAccessKey'
  | 'awsSessionToken'
  | 'endPointURL'
>;

export type DbtS3CredsReq = Pick<DbtS3Creds, 'awsRegion'>;

export type DbtConfigCloudReq = Pick<
  DbtConfigCloud,
  'dbtCloudAccountId' | 'dbtCloudAuthToken'
>;

export interface DbtSourceTypes {
  sourceType: DBT_SOURCES;
  gcsType?: GCS_CONFIG;
}

export type DbtGCSCreds = GCPCredentialsValues;

export type ErrorDbtCloud = Record<keyof DbtConfigCloud, string>;

export type ErrorDbtLocal = Record<keyof DbtConfigLocal, string>;

export type ErrorDbtHttp = Record<keyof DbtConfigHttp, string>;

export type ErrorDbtS3 = Record<keyof DbtS3Creds, string>;

export type ErrorDbtGCS = { gcsConfig: string } & Record<
  keyof DbtGCSCreds,
  string
>;
