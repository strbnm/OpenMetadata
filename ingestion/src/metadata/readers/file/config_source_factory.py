#  Copyright 2021 Collate
#  Licensed under the Apache License, Version 2.0 (the "License");
#  you may not use this file except in compliance with the License.
#  You may obtain a copy of the License at
#  http://www.apache.org/licenses/LICENSE-2.0
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
#  limitations under the License.

"""
ConfigSource Reader Factory: Helps us choose the reader from
- Local
- ADLS
- S3
- GCS
"""
from typing import Any

from metadata.generated.schema.entity.services.connections.database.datalake.azureConfig import (
    AzureConfig,
)
from metadata.generated.schema.entity.services.connections.database.datalake.gcsConfig import (
    GCSConfig,
)
from metadata.generated.schema.entity.services.connections.database.datalake.s3Config import (
    S3Config,
)
from metadata.generated.schema.entity.services.connections.database.datalakeConnection import (
    LocalConfig,
)
from metadata.readers.file.adls import ADLSReader
from metadata.readers.file.base import Reader
from metadata.readers.file.gcs import GCSReader
from metadata.readers.file.local import LocalReader
from metadata.readers.file.s3 import S3Reader
from metadata.readers.models import ConfigSource

CONFIG_SOURCE_READER = {
    LocalConfig.__name__: LocalReader,
    AzureConfig.__name__: ADLSReader,
    GCSConfig.__name__: GCSReader,
    S3Config.__name__: S3Reader,
}


def get_reader(config_source: ConfigSource, client: Any) -> Reader:
    """
    Load the File Reader based on the Config Source
    """
    config_source_type_name = type(config_source).__name__
    if config_source_type_name in CONFIG_SOURCE_READER:
        return CONFIG_SOURCE_READER[config_source_type_name](client)

    raise NotImplementedError(
        f"Reader for [{config_source_type_name}] is not implemented."
    )
