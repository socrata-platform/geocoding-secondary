## geocoding-secondary
Asynchronous NBE geocoding service

## Design
**geocoding-secondary** is a _feedback_ secondary. [What is a feedback secondary?](https://docs.google.com/document/d/1feNpBc8mbEi5CF7sDmvASkMyISJhLofbPuFM4jaNL14/edit) Like other secondaries it runs as an instance of secondary-watcher. But instead of writing to its own store, it _feeds back_ its computations to `truth` by posting mutation scripts to data-coordinator.

## Usage
**geocoding-secondary** operates on _computed point_ columns with computation strategy `"type" : "geocoding"`. It geocodes the value of the target point column from an address constructed from the text source columns described in the `computationStrategy`.

#### Computed Column Definition
For a _geocoded_ computed column
* the column `dataTypeName` must be `"point"`.
* the column `computationStrategy.type` must be `"geocoding"`.
* source columns must be `text` columns.\*

The computation strategy has the following _required_\*\* parameter:
* `"country_default"`: country address default value

\*\* A customer can set a domain wide default for `country_default` which will be used unless it is overridden at the computation strategy level. If there is no domain default and the parameter is not provided to the computation strategy, it will be inserted with value `"US"`.

And the following _optional_ parameters:

Source columns:
* `"address"`: field name of the address source column
* `"locality"`: field name of the locality source column
* `"subregion"`: field name of the subregion source column
* `"region"`: field name of the region source column
* `"postal_code"`: field name of the postal code source column
* `"country"`: field name of country source column

Default values:
* `"address_default"`: address default value
* `"locality_default"`: locality default value
* `"subregion_default"`: subregion default value
* `"region_default"`: region default value
* `"postal_code_default"`: postal code default value

The source column field names in the `computationStrategy.source_columns` and `computationStrategy.parameters` must match, further those columns must exist and cannot be deleted until the computed column is first deleted.

If a default value is specified for an address part, then that value will be used in the computation always if no source column is specified for that address part, otherwise it will be used as default value for that address part in the computation for null values at the row level.

Version:
* `"version"`: api version, this version will be `"v1"`

If the api version is not provided the version will default to the current version and be inserted into the computation strategy.

For example:
```
{
  "name": "Location",
  "dataTypeName": "point",
  "fieldName": "location",
  "computationStrategy": {
    "type": "geocoding",
    "source_columns": [ "street_address", "city", "state", "zip_code"],
    "parameters": {
      "address": "street_address",
      "locality": "city",
      "region": "state",
      "postal_code": "zip_code",
      "region_default": "WA",
      "country_default": "US",
      "version": "v1"
    }
  }
}
```

\* `postal_code` source columns may be `number` columns, but really postal codes _should_ be of type `text`.

## Running
To run the tests
```
sbt test
```
