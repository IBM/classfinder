# classfinder

Find a Java class in a folder. Automatically expands `*.zip`, `*.jar`, and `*.jmod` files, searches them, and deletes the extracted directories afterwards.

## usage

Download `classfinder.jar` from the latest release at <https://github.com/IBM/classfinder/releases/latest>

```
usage: java -jar classfinder.jar CLASS [DIRECTORY...]
```

Currently, `CLASS` does not support package names.

## Development

1. Build:
   ```
   mvn clean install
   ```
