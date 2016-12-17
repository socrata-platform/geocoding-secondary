## geocoding-secondary
Asynchronous NBE geocoding and region coding service

## Design
**geocoding-secondary** is a _feedback_ secondary. [What is a feedback secondary?](https://docs.google.com/document/d/1feNpBc8mbEi5CF7sDmvASkMyISJhLofbPuFM4jaNL14/edit) Like other secondaries it runs as an instance of secondary-watcher. But instead of writing to its own store, it _feeds back_ its computations to `truth` by posting mutation scripts to data-coordinator.

## Usage
**geocoding-secondary** operates on _computed columns_ with computation strategies of the following types:
* [`geocoding`](README.md#geocoding)
* [`georegion_match_on_point`](README.md#georegion-match-on-point) or legacy `georegion`
* [`georegion_match_on_string`](README.md#georegion-match-on-string)

It computes the value of the target computed column from the source columns described in the `computationStrategy`.

## Computed Column Definitions
Each computation strategy type defines what should be found in `parameters`.

We have a strategy definition validation library for [computation strategies](https://github.com/socrata-platform/computation-strategies).
### Geocoding
**geocoding-secondary** geocodes the value of the target _point_ column from an _address_ constructed from the _text_ source columns described in the `computationStrategy`.

For a _geocoded_ computed column of type `"geocoding"`
* the column `dataTypeName` must be `"point"`.
* the column `computationStrategy.type` must be `"geocoding"`.
* source columns must be `text` columns.\*

The computation strategy has the following _required_\*\* parameter:
* `"defaults.country"`: country address default value

\*\* A superadmin can set a [domain wide default](README.md#domain-wide-defaults) for `defaults.country` which will be used unless it is overridden at the computation strategy level. If there is no domain default and the parameter is not provided to the computation strategy, it will be inserted with value `"US"`.

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

### Region Coding
**geocoding-secondary** region codes the value of the target _number_ column from either a _lat/lon_ or _string_ value from the _point_ or _text_ source column described in the `computationStrategy` using the specified curated region dataset found in `parameters`.

#### Georegion Match On Point
For a _region coded_ computed column of type `"georegion_match_on_point"` (legacy `"georegion"`)
* the column `dataTypeName` must be `"number"`.
* the column `computationStrategy.type` must be `"georegion_match_on_point"` (or legacy `"georegion"`).
* a single source column must be of type `"point"`.

The computation strategy has the following _required_ parameters:
* `"region"` which is the resource name of the curated region
* `"primary_key"` which is the primary key of the curated region

For example:
```
{ "type": "georegion_match_on_point",
  "source_columns": ["location_point"],
  "parameters": {
    "region": "_nmuc-gpu5",
    "primary_key": "_feature_id"
  }
}
```

#### Georegion Match On String
For a _region coded_ computed column of type `"georegion_match_on_string"`
* the column `dataTypeName` must be `"number"`.
* the column `computationStrategy.type` must be `"georegion_match_on_string"`.
* a single source column must be of type `"text"`.

The computation strategy has the following _required_ parameters:
* `"region"` which is the resource name of the curated region
* `"column"` TODO: what is this?
* `"primary_key"` which is the primary key of the curated region

For example:
```
{ "type": "georegion_match_on_string",
  "source_columns": ["location_string"],
  "parameters": {
    "region": "_nmuc-gpu5",
    "column": "column_1",
    "primary_key": "_feature_id"
  }
}
```

## Running
### Configuration
If you want to use the geocoding secondary you will need to add a MapQuest app-token to the config and add it to the `secondary_stores_config` table in `datacoordinator` (truth).
```
INSERT INTO secondary_stores_config (store_id, next_run_time, interval_in_seconds, is_feedback_secondary) VALUES( 'geocoding', now(), 5, true);
```
#### MapQuest
For `geocoding-secondary` to use MapQuest add to your config under `com.socrata.geocoding-secondary.geocoder`
```
mapquest {
  app-token = "SOME MAPQUEST APP TOKEN"
  retry-count = 5
}
```

### Running
```
> sbt assembly
> java -Djava.net.preferIPv4Stack=true -Dconfig.file=/etc/geocoding-secondary.conf -jar target/scala-2.10/secondary-watcher-geocoding-assembly-0.0.12-SNAPSHOT.jar
```

## Tests
To run the tests
```
sbt test
```
