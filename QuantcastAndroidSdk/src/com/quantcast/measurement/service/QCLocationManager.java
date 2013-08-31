/**
 * Copyright 2013 Quantcast Corp.
 *
 * This software is licensed under the Quantcast Mobile App Measurement Terms of Service
 * https://www.quantcast.com/learning-center/quantcast-terms/mobile-app-measurement-tos
 * (the “License”). You may not use this file unless (1) you sign up for an account at
 * https://www.quantcast.com and click your agreement to the License and (2) are in
 *  compliance with the License. See the License for the specific language governing
 * permissions and limitations under the License.
 *
 */
package com.quantcast.measurement.service;

import android.content.Context;
import android.location.Address;
import android.location.Criteria;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;


final class QCLocationManager {

    private static final QCLog.Tag TAG = new QCLog.Tag(QCLocationManager.class);

    private static final int STALE_LIMIT = 1000 * 60 * 10;  //10 minutes
    private final LocationManager _locManager;

    private String _myProvider;
    private AsyncTask _geoTask;
    private Geocoder _geocoder;

    QCLocationManager(Context appContext) {
        //loop through every provider and try to find a location
        _locManager = (LocationManager) appContext.getSystemService(Context.LOCATION_SERVICE);

        if (_locManager != null) {
            //specifically set our Criteria. All we need is a general location
            Criteria criteria = new Criteria();
            criteria.setAccuracy(Criteria.ACCURACY_COARSE);
            criteria.setAltitudeRequired(false);
            criteria.setBearingRequired(false);
            criteria.setCostAllowed(false);
            criteria.setPowerRequirement(Criteria.NO_REQUIREMENT);
            criteria.setSpeedRequired(false);

            _myProvider = _locManager.getBestProvider(criteria, true);

            _geocoder = new Geocoder(appContext);
        }

    }

    //check all providers for any recently cached data see if we can save a call
    Location findBestLocation() {
        Location retval = null;
        long acceptableTimestamp = System.currentTimeMillis() - STALE_LIMIT;
        float bestAccuracy = Float.MAX_VALUE;
        List<String> matchingProviders = _locManager.getAllProviders();
        for (String provider : matchingProviders) {
            Location location = _locManager.getLastKnownLocation(provider);
            if (location != null) {
                float accuracy = location.getAccuracy();
                long time = location.getTime();

                //accuracy of 0 means unknown, not perfect accuracy
                if (accuracy > 0.0 && time >= acceptableTimestamp && accuracy <= bestAccuracy) {
                    retval = location;
                    bestAccuracy = accuracy;
                    acceptableTimestamp = time;
                }
            }
        }
        return retval;
    }

    void start() {

        Location bestLocation = findBestLocation();
        if (bestLocation != null) {
            sendLocation(bestLocation);
        } else if (_myProvider != null) {
            try {
                if (_locManager.isProviderEnabled(_myProvider)) {
                    _locManager.requestLocationUpdates(_myProvider, 0, 0, singleUpdateListener, Looper.getMainLooper());
                }
            } catch (Exception e) {
                QCLog.e(TAG, "Available location provider not found.  Skipping Location Event", e);
            }
        } else {
            QCLog.i(TAG, "Available location provider not found.  Skipping Location Event");
        }

    }

    void stop() {
        _locManager.removeUpdates(singleUpdateListener);
        if (null != _geoTask && _geoTask.getStatus() != AsyncTask.Status.FINISHED) {
            _geoTask.cancel(true);
        }
        _geoTask = null;
    }

    Geocoder getGeocoder() {
        return _geocoder;
    }

    private void sendLocation(Location location) {
        Double lat = location.getLatitude();
        Double longTemp = location.getLongitude();

        _geoTask = new AsyncTask<Double, Void, MeasurementLocation>() {
            @Override
            protected MeasurementLocation doInBackground(Double... params) {
                MeasurementLocation retval;
                double latitude = params[0];
                double longitude = params[1];
                QCLog.i(TAG, "Looking for address.");
                try {
                    QCLog.i(TAG, "Geocoder.");
                    List<Address> addresses = _geocoder.getFromLocation(latitude, longitude, 1);
                    if (addresses != null && addresses.size() > 0) {
                        Address address = addresses.get(0);
                        retval = new MeasurementLocation(address.getCountryCode(), address.getAdminArea(), address.getLocality());
                    } else {
                        QCLog.i(TAG, "Geocoder reverse lookup failed.");
                        retval = this.fallbackGeoLocate(latitude, longitude);
                    }
                } catch (Exception e) {
                    QCLog.i(TAG, "Geocoder API not available.");
                    retval = this.fallbackGeoLocate(latitude, longitude);
                }
                return retval;

            }

            protected MeasurementLocation fallbackGeoLocate(double latitude, double longitude) {
                MeasurementLocation retval = null;
                // call googles map api directly
                MeasurementLocation geoInfo = lookup(latitude, longitude);
                if (geoInfo != null && !this.isCancelled()) {
                    retval = geoInfo;
                } else {
                    QCLog.i(TAG, "Google Maps API reverse lookup failed.");
                }
                return retval;
            }

            @Override
            protected void onPostExecute(MeasurementLocation address) {
                if (null != address && address.getCountry() != null) {
                    QCLog.i(TAG, "Got address and sending..." + address.getCountry() + " " + address.getState() + " " + address.getLocality());
                    QCMeasurement.INSTANCE.logLocation(address.getCountry(), address.getState(), address.getLocality());
                }
            }
        }.execute(lat, longTemp);
    }

    protected final LocationListener singleUpdateListener = new LocationListener() {
        public void onLocationChanged(Location location) {
            _locManager.removeUpdates(singleUpdateListener);
            if (location != null) {
                sendLocation(location);
            }
        }

        public void onStatusChanged(String provider, int status, Bundle extras) {
        }

        public void onProviderEnabled(String provider) {
        }

        public void onProviderDisabled(String provider) {
        }
    };


    //Reverse lookup service using google maps just in case an on device geocoder doesn't exist
    private static final String STATUS = "status";
    private static final String OK = "OK";
    private static final String URL = "https://maps.googleapis.com/maps/api/geocode/json?sensor=true&latlng=";
    private static final String ADDRESS = "address_components";
    private static final String RESULTS = "results";
    private static final String LOCALITY = "locality";
    private static final String SHORT_NAME = "short_name";
    private static final String STATE = "administrative_area_level_1";
    private static final String COUNTRY = "country";
    private static final String TYPES = "types";

    private MeasurementLocation lookup(double lat, double lng) {
        String urlStr = URL + lat + "," + lng;
        try {
            java.net.URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
                builder.append("\n");
            }
            try {
                return parseJson(builder.toString());
            } catch (JSONException jsonException) {
                QCLog.e(TAG, "Unable to get address from JSON", jsonException);
            }
        } catch (Exception mapsException) {
            QCLog.e(TAG, "Exception thrown by Google Maps", mapsException);
        }
        return null;
    }

    private MeasurementLocation parseJson(String jsonString) throws JSONException {
        JSONObject json = new JSONObject(jsonString);
        if (OK.equals(json.optString(STATUS))) {
            JSONArray resultsArray = json.optJSONArray(RESULTS);
            if (resultsArray != null) {
                JSONArray addressComponents;
                for (int i = 0; i < resultsArray.length(); i++) {
                    addressComponents = resultsArray.getJSONObject(i).optJSONArray(ADDRESS);
                    if (addressComponents != null) {
                        String country = "", locality = "", state = "";
                        for (int j = 0; j < addressComponents.length(); j++) {
                            JSONObject obj = addressComponents.getJSONObject(j);
                            JSONArray types = obj.optJSONArray(TYPES);
                            if (types != null) {
                                if (containsString(types, LOCALITY))
                                    locality = obj.getString(SHORT_NAME);
                                if (containsString(types, STATE))
                                    state = obj.getString(SHORT_NAME);
                                if (containsString(types, COUNTRY))
                                    country = obj.getString(SHORT_NAME);
                            }
                        }
                        return new MeasurementLocation(country, state, locality);
                    }
                }
            }
        }
        return null;
    }

    private boolean containsString(JSONArray arr, String s) {
        String tmp;
        for (int i = 0; i < arr.length(); i++) {
            tmp = arr.optString(i);
            if (tmp != null && tmp.equals(s))
                return true;
        }
        return false;
    }

    //Helper class to pass data around
    private class MeasurementLocation {
        private final String country;
        private final String state;
        private final String locality;

        public MeasurementLocation(String country, String state, String locality) {
            this.country = country;
            this.state = state;
            this.locality = locality;
        }

        public String getCountry() {
            return country;
        }

        public String getState() {
            return state;
        }

        public String getLocality() {
            return locality;
        }
    }

}
