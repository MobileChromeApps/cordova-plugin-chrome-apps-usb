# chrome.usb Plugin

This plugin provides USB connectivity for Android.

## Status

Beta on Android. iOS not supported.

## Reference

The API reference is [here](https://developer.chrome.com/apps/usb).

# Release Notes

## 1.2.0 (March, 2016)
- Adds chrome.usb.cordova.hasUsbHostFeature()
- Moves getDevices() to a background thread

## 1.1.0 (Feb, 2016)
- Adds support for chrome.usb.interruptTransfer
- Fix for multiple usage of openDevice
- Implements getDevices filtering
- Set USB feature as optional
- Implemented dynamic USB permission request
- Implemented 'recipient' field in controlTransfer
- Implemented asynchronous control transfer
- Implemented 'timeout' parameter

## 1.0.1 (April 30, 2015)
- Renamed plugin to pubilsh to NPM

## 1.0.0 (March 30, 2015)
- Initial release
