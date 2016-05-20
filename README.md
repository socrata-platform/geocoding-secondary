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
* `"defaults.country"`: country address default value

\*\* A superadmin can set a [domain wide default](https://github.com/socrata-platform/geocoding-secondary/blob/aerust/EN-4817/README.md#domain-wide-defaults) for `defaults.country` which will be used unless it is overridden at the computation strategy level. If there is no domain default and the parameter is not provided to the computation strategy, it will be inserted with value `"US"`.

And the following _optional_ parameters:

Source columns:
* `"sources.address"`: field name of the address source column
* `"sources.locality"`: field name of the locality source column
* `"sources.subregion"`: field name of the subregion source column
* `"sources.region"`: field name of the region source column
* `"sources.postal_code"`: field name of the postal code source column
* `"sources.country"`: field name of country source column

Default values:
* `"defaults.address"`: address default value
* `"defaults.locality"`: locality default value
* `"defaults.subregion"`: subregion default value
* `"defaults.region"`: region default value
* `"defaults.postal_code"`: postal code default value

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
      "sources": {
        "address": "street_address",
        "locality": "city",
        "region": "state",
        "postal_code": "zip_code"
      },
      "defaults": {
        "region": "WA",
        "country": "US"
      },
      "version": "v1"
    }
  }
}
```

\* `postal_code` source columns may be `number` columns, but really postal codes _should_ be of type `text`.

##### Domain Wide Defaults
A superadmin can set a domain wide default for any of the default values. These domain wide default values will be used unless overridden in a computation strategy.

These can be set through the admin panel by providing the property to the `"geocoding"` configuration:

* key: `"defaults"`  
* value:
  A json object with the optional fields:
  - `"address"`
  - `"locality"`
  - `"subregion"`
  - `"region"`
  - `"postal_code"`
  - `"country"`

Setting a domain wide default for `"country"` is encouraged as otherwise `"computationStrategy.defaults.country"` will be defaulted to `"US"`.

## Running
To run the tests
```
sbt test
```
