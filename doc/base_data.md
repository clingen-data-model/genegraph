# Base data
(genegraph.sink.base)

While the main focus of data ingest in Genegraph is through streaming sources, there is a need to incorporate one-time data from bulk sources at the beginning of every build. These sources are described in resources/base.edn. Facilities exist for downloading fresh copies of this data and uploading them to the Google Cloud. When a new build is requested, the version of files stored in current-base/ in the Genegraph cloud bucket is downloaded and incorporated into the Jena database

## Fields in base.edn

  * name: graph name to be used in Jena on import
  * source: HTTP(S) or FTP source from which the file can be downloaded
  * target: local filename for the resource
  * format: format the file is stored in, maps to the transformer needed to load the data
  * reader-opts: special options the transformer may need to interpret the data

## Refreshing the data

Specific functions are used for updating the current version of the base data in the cloud

### genegraph.sink.base/retrieve-base-data! 

Download all required base data from the origin to the localhost

### genegraph.sink.base/push-base-data-to-gcp!

Upload all local base data into the cloud
