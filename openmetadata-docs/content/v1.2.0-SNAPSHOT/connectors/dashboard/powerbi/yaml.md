---
title: Run the PowerBI Connector Externally
slug: /connectors/dashboard/powerbi/yaml
---

# Run the PowerBI Connector Externally

| Stage      | PROD                         |
|------------|------------------------------|
| Dashboards | {% icon iconName="check" /%} |
| Charts     | {% icon iconName="check" /%} |
| Owners     | {% icon iconName="cross" /%} |
| Tags       | {% icon iconName="cross" /%} |
| Datamodels | {% icon iconName="check" /%} |
| Lineage    | {% icon iconName="check" /%} |

In this section, we provide guides and references to use the PowerBI connector.

Configure and schedule PowerBI metadata and profiler workflows from the OpenMetadata UI:

- [Requirements](#requirements)
- [Metadata Ingestion](#metadata-ingestion)

{% partial file="/v1.2.0/connectors/external-ingestion-deployment.md" /%}

## Requirements

{%inlineCallout icon="description" bold="OpenMetadata 0.12 or later" href="/deployment"%}
To deploy OpenMetadata, check the Deployment guides.
{%/inlineCallout%}

To access the PowerBI APIs and import dashboards, charts, and datasets from PowerBI into OpenMetadata, a `PowerBI Pro` license is necessary.

### PowerBI Account Setup

### Step 1: Create an Azure AD app and configure the PowerBI Admin consle

Please follow the steps mentioned [here](https://docs.microsoft.com/en-us/power-bi/developer/embedded/embed-service-principal) for setting up the Azure AD application service principle and configure PowerBI admin settings

Login to [Power BI](https://app.powerbi.com/) as Admin and from `Tenant` settings allow below permissions.
- Allow service principles to use Power BI APIs
- Allow service principals to use read-only Power BI admin APIs
- Enhance admin APIs responses with detailed metadata

### Step 2: Provide necessary API permissions to the app
Go to the `Azure Ad app registrations` page, select your app and add the dashboard permissions to the app for PowerBI service and grant admin consent for the same:

The required permissions are:
- `Dashboard.Read.All`

Optional Permissions: (Without granting these permissions, the dataset information cannot be retrieved and the datamodel and lineage processing will be skipped)
- `Dataset.Read.All`

{% note %}

Make sure that in the API permissions section **Tenant** related permissions are not being given to the app
Please refer [here](https://stackoverflow.com/questions/71001110/power-bi-rest-api-requests-not-authorizing-as-expected) for detailed explanation 

{% /note %}

### Step 3: Create New PowerBI workspace
The service principal only works with [new workspaces](https://docs.microsoft.com/en-us/power-bi/collaborate-share/service-create-the-new-workspaces).
[For reference](https://community.powerbi.com/t5/Service/Error-while-executing-Get-dataset-call-quot-API-is-not/m-p/912360#M85711)

### Python Requirements

To run the PowerBI ingestion, you will need to install:

```bash
pip3 install "openmetadata-ingestion[powerbi]"
```

## Metadata Ingestion

All connectors are defined as JSON Schemas.
[Here](https://github.com/open-metadata/OpenMetadata/blob/main/openmetadata-spec/src/main/resources/json/schema/entity/services/connections/dashboard/powerBIConnection.json)
you can find the structure to create a connection to PowerBI.

In order to create and run a Metadata Ingestion workflow, we will follow
the steps to create a YAML configuration able to connect to the source,
process the Entities if needed, and reach the OpenMetadata server.

The workflow is modeled around the following
[JSON Schema](https://github.com/open-metadata/OpenMetadata/blob/main/openmetadata-spec/src/main/resources/json/schema/metadataIngestion/workflow.json)

### 1. Define the YAML Config

This is a sample config for PowerBI:

{% codePreview %}

{% codeInfoContainer %}

#### Source Configuration - Service Connection

{% codeInfo srNumber=1 %}

**clientId**: PowerBI Client ID.

To get the client ID (also know as application ID), follow these steps:
- Log into [Microsoft Azure](https://ms.portal.azure.com/#allservices).
- Search for App registrations and select the App registrations link.
- Select the Azure AD app you're using for embedding your Power BI content.
- From the Overview section, copy the Application (client) ID.

{% /codeInfo %}

{% codeInfo srNumber=2 %}

**clientSecret**: PowerBI Client Secret.

To get the client secret, follow these steps:
- Log into [Microsoft Azure](https://ms.portal.azure.com/#allservices).
- Search for App registrations and select the App registrations link.
- Select the Azure AD app you're using for embedding your Power BI content.
- Under Manage, select Certificates & secrets.
- Under Client secrets, select New client secret.
- In the Add a client secret pop-up window, provide a description for your application secret, select when the application secret expires, and select Add.
- From the Client secrets section, copy the string in the Value column of the newly created application secret.

{% /codeInfo %}

{% codeInfo srNumber=3 %}

**tenantId**: PowerBI Tenant ID.

To get the tenant ID, follow these steps:
- Log into [Microsoft Azure](https://ms.portal.azure.com/#allservices).
- Search for App registrations and select the App registrations link.
- Select the Azure AD app you're using for Power BI.
- From the Overview section, copy the Directory (tenant) ID.

{% /codeInfo %}

{% codeInfo srNumber=4 %}

**scope**: Service scope.

To let OM use the Power BI APIs using your Azure AD app, you'll need to add the following scopes:
- https://analysis.windows.net/powerbi/api/.default

Instructions for adding these scopes to your app can be found by following this link: https://analysis.windows.net/powerbi/api/.default.

{% /codeInfo %}

{% codeInfo srNumber=5 %}

**authorityUri**: Authority URI for the service.

To identify a token authority, you can provide a URL that points to the authority in question.

If you don't specify a URL for the token authority, we'll use the default value of https://login.microsoftonline.com/.

{% /codeInfo %}

{% codeInfo srNumber=6 %}

**hostPort**: URL to the PowerBI instance.

To connect with your Power BI instance, you'll need to provide the host URL. If you're using an on-premise installation of Power BI, this will be the domain name associated with your instance.

If you don't specify a host URL, we'll use the default value of https://app.powerbi.com to connect with your Power BI instance.

{% /codeInfo %}

{% codeInfo srNumber=7 %}

**Pagination Entity Per Page**:

The pagination limit for Power BI APIs can be set using this parameter. The limit determines the number of records to be displayed per page.

By default, the pagination limit is set to 100 records, which is also the maximum value allowed.
{% /codeInfo %}

{% codeInfo srNumber=8 %}

**Use Admin APIs**:

Option for using the PowerBI admin APIs:
- Enabled (Use PowerBI Admin APIs)
Using the admin APIs will fetch the dashboard and chart metadata from all the workspaces available in the PowerBI instance.

{% note %} 

When using the PowerBI Admin APIs there are no limitations on the Datasets that are retrieved for creating lineage information.

{% /note %}

- Disabled (Use Non-Admin PowerBI APIs)
Using the non-admin APIs will only fetch the dashboard and chart metadata from the workspaces that have the security group of the service principal assigned to them.

{% note %} 

When using the PowerBI Non-Admin APIs, the lineage information can only be generated if the dataset is a [Push Dataset](https://learn.microsoft.com/en-us/rest/api/power-bi/push-datasets).
For more information please visit the PowerBI official documentation [here](https://learn.microsoft.com/en-us/rest/api/power-bi/push-datasets/datasets-get-tables#limitations).

{% /note %}

{% /codeInfo %}

#### Source Configuration - Source Config

{% codeInfo srNumber=9 %}

The `sourceConfig` is defined [here](https://github.com/open-metadata/OpenMetadata/blob/main/openmetadata-spec/src/main/resources/json/schema/metadataIngestion/dashboardServiceMetadataPipeline.json):

- **dbServiceNames**: Database Service Names for ingesting lineage if the source supports it.
- **dashboardFilterPattern**, **chartFilterPattern**, **dataModelFilterPattern**: Note that all of them support regex as include or exclude. E.g., "My dashboard, My dash.*, .*Dashboard".
- **includeOwners**: Set the 'Include Owners' toggle to control whether to include owners to the ingested entity if the owner email matches with a user stored in the OM server as part of metadata ingestion. If the ingested entity already exists and has an owner, the owner will not be overwritten.
- **includeTags**: Set the 'Include Tags' toggle to control whether to include tags in metadata ingestion.
- **includeDataModels**: Set the 'Include Data Models' toggle to control whether to include tags as part of metadata ingestion.
- **markDeletedDashboards**: Set the 'Mark Deleted Dashboards' toggle to flag dashboards as soft-deleted if they are not present anymore in the source system.

{% /codeInfo %}

#### Sink Configuration

{% codeInfo srNumber=10 %}

To send the metadata to OpenMetadata, it needs to be specified as `type: metadata-rest`.

{% /codeInfo %}

{% partial file="/v1.2.0/connectors/workflow-config.md" /%}

{% /codeInfoContainer %}

{% codeBlock fileName="filename.yaml" %}

```yaml
source:
  type: powerbi
  serviceName: local_powerbi
  serviceConnection:
    config:
      type: PowerBI
```
```yaml {% srNumber=1 %}
      clientId: clientId
```
```yaml {% srNumber=2 %}
      clientSecret: secret
```
```yaml {% srNumber=3 %}
      tenantId: tenant
```
```yaml {% srNumber=4 %}
      # scope:
      #    - https://analysis.windows.net/powerbi/api/.default (default)
```
```yaml {% srNumber=5 %}
      # authorityURI: https://login.microsoftonline.com/ (default)
```
```yaml {% srNumber=6 %}
      # hostPort: https://analysis.windows.net/powerbi (default)
```
```yaml {% srNumber=7 %}
      # pagination_entity_per_page: 100 (default)
```
```yaml {% srNumber=8 %}
      # useAdminApis: true (default)
```
```yaml {% srNumber=9 %}
  sourceConfig:
    config:
      type: DashboardMetadata
      # dbServiceNames:
      #   - service1
      #   - service2
      # dashboardFilterPattern:
      #   includes:
      #     - dashboard1
      #     - dashboard2
      #   excludes:
      #     - dashboard3
      #     - dashboard4
      # chartFilterPattern:
      #   includes:
      #     - chart1
      #     - chart2
      #   excludes:
      #     - chart3
      #     - chart4
```
```yaml {% srNumber=10 %}
sink:
  type: metadata-rest
  config: {}
```

{% partial file="/v1.2.0/connectors/workflow-config-yaml.md" /%}

{% /codeBlock %}

{% /codePreview %}

### 2. Run with the CLI

First, we will need to save the YAML file. Afterward, and with all requirements installed, we can run:

```bash
metadata ingest -c <path-to-yaml>
```

Note that from connector to connector, this recipe will always be the same. By updating the YAML configuration,
you will be able to extract metadata from different sources.
