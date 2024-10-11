# classfinder

Find a Java class in a folder. Automatically expands `*.zip`, `*.jar`, and `*.jmod` files, searches them, and deletes the extracted directories afterwards.

## usage

Download `classfinder.jar` from the latest release at <https://github.com/IBM/classfinder/releases/latest>

```
usage: java -jar classfinder.jar CLASS [DIRECTORY...]
```

Currently, `CLASS` does not support package names.

### Trace

```
-Djava.util.logging.config.file=/$PATH/logging.properties
```

## Development

### New Release

1. Update `version` in `pom.xml`
1. Build:
   ```
   mvn clean install
   ```
1. Commit and push
1. Wait for the GitHub Action build to succeed
1. Tag and push:
   ```
   git tag -l
   git tag 0.X.Y
   git push --tags
   ```
