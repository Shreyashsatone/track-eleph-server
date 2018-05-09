package org.traccar.processing.peripheralsensorprocessors.fuelsensorprocessors;

import com.google.common.collect.BoundType;
import com.google.common.collect.SortedMultiset;
import com.google.common.collect.TreeMultiset;
import org.apache.commons.lang.StringUtils;
import org.traccar.BaseDataHandler;
import org.traccar.Context;
import org.traccar.helper.Log;
import org.traccar.model.Device;
import org.traccar.model.PeripheralSensor;
import org.traccar.model.Position;
import org.traccar.processing.peripheralsensorprocessors.fuelsensorprocessors.FuelActivity.FuelActivityType;
import org.traccar.transforms.model.FuelSensorCalibration;
import org.traccar.transforms.model.SensorPointsMap;

import java.util.Calendar;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Date;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class FuelSensorDataHandler extends BaseDataHandler {

    private static final int INVALID_FUEL_FREQUENCY = 0xFFF;
    private static final String FUEL_ANALOG = "FUEL_ANALOG";
    private static final String SENSOR_ID = "sensorId";
    private static final String SENSOR_DATA = "sensorData";
    private static final String ADC_1 = "adc1";
    private static final String FREQUENCY_PREFIX = "F=";
    private static final String FUEL_PART_PREFIX = "N=";

    private int minValuesForMovingAvg;
    private int maxMessagesToLoad;
    private int maxValuesForAlerts;
    private int storedEventLookAroundSeconds;
    private int currentEventLookBackSeconds;
    private double fuelLevelChangeThresholdLitresDigital;
    private double fuelLevelChangeThresholdLitresAnalog;
    private double fuelErrorThreshold;

    private final Map<String, TreeMultiset<Position>> previousPositions =
            new ConcurrentHashMap<>();

    private final Map<String, FuelEventMetadata> deviceFuelEventMetadata =
            new ConcurrentHashMap<>();

    private boolean loadingOldDataFromDB;

    public FuelSensorDataHandler() {
        initializeConfig();
        loadOldPositions();
    }

    public FuelSensorDataHandler(boolean loader) {
        // Do nothing constructor for tests.
    }

    private void initializeConfig() {
        int messageFrequency = Context.getConfig().getInteger("processing.peripheralSensorData.messageFrequency");
        int hoursOfDataToLoad = Context.getConfig()
                                       .getInteger("processing.peripheralSensorData.hoursOfDataToLoad");

        minValuesForMovingAvg = Context.getConfig()
                                       .getInteger("processing.peripheralSensorData.minValuesForMovingAverage");
        maxMessagesToLoad = (3600 * hoursOfDataToLoad) / messageFrequency;

        maxValuesForAlerts = Context.getConfig()
                                    .getInteger("processing.peripheralSensorData.maxValuesForAlerts");
        storedEventLookAroundSeconds =
                Context.getConfig()
                       .getInteger("processing.peripheralSensorData.storedEventLookAroundSeconds");

        currentEventLookBackSeconds =
                Context.getConfig()
                       .getInteger("processing.peripheralSensorData.currentEventLookBackSeconds");

        fuelLevelChangeThresholdLitresDigital =
                Context.getConfig()
                       .getDouble("processing.peripheralSensorData.fuelLevelChangeThresholdLitersDigital");

        fuelLevelChangeThresholdLitresAnalog =
                Context.getConfig()
                       .getDouble("processing.peripheralSensorData.fuelLevelChangeThresholdLitersAnalog");

        fuelErrorThreshold = Context.getConfig().getDouble("processing.peripheralSensorData.fuelErrorThreshold");
    }

    @Override
    protected Position handlePosition(Position position) {
        try {
            Optional<List<PeripheralSensor>> peripheralSensorsOnDevice =
                    getLinkedDevices(position.getDeviceId());

            if (!peripheralSensorsOnDevice.isPresent()) {
                return position;
            }

            Optional<Integer> sensorIdOnPosition =
                    getSensorId(position, peripheralSensorsOnDevice.get());

            if (!sensorIdOnPosition.isPresent()) {
                return position;
            }

            if (position.getAttributes().containsKey(SENSOR_DATA)) {
                // Digital fuel sensor data
                handleDigitalFuelSensorData(position, sensorIdOnPosition.get(), fuelLevelChangeThresholdLitresDigital);
            }

            if (position.getAttributes().containsKey(ADC_1)) {
                handleAnalogFuelSensorData(position, sensorIdOnPosition.get(), fuelLevelChangeThresholdLitresAnalog);
            }
        } catch (Exception e) {
//            Log.info(String.format("Exception in processing fuel info: %s", e.getMessage()));
            e.printStackTrace();
        } finally {
            return position;
        }
    }

    private void loadOldPositions() {
        this.loadingOldDataFromDB = true;
        Collection<Device> devices = Context.getDeviceManager().getAllDevices();

        Date oneDayAgo = getAdjustedDate(new Date(), Calendar.DAY_OF_MONTH, -1);

        for (Device device : devices) {
            Optional<List<PeripheralSensor>> linkedDevices =
                    getLinkedDevices(device.getId());

            if (!linkedDevices.isPresent()) {
                continue;
            }

            try {
                Collection<Position> devicePositionsInLastDay =
                        Context.getDataManager().getPositions(device.getId(), oneDayAgo, new Date());

                for (Position position : devicePositionsInLastDay) {
                    handlePosition(position);
                }
                this.loadingOldDataFromDB = false;

            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private Optional<List<PeripheralSensor>> getLinkedDevices(long deviceId) {
        return Context.getPeripheralSensorManager()
                      .getLinkedPeripheralSensors(deviceId);
    }

    private Optional<Integer> getSensorId(Position position, List<PeripheralSensor> peripheralSensorsOnDevice) {

        if (position.getAttributes().containsKey(SENSOR_ID)) {
            final int positionSensorId = (int) position.getAttributes().get(SENSOR_ID);
            Optional<PeripheralSensor> digitalSensor =
                    peripheralSensorsOnDevice.stream()
                                             .filter(p -> p.getPeripheralSensorId() == positionSensorId).findFirst();

            return digitalSensor.map(sensor -> (int) sensor.getPeripheralSensorId());
        }

        if (position.getAttributes().containsKey(ADC_1)) {
            Optional<PeripheralSensor> analogSensor =
                    peripheralSensorsOnDevice.stream()
                                             .filter(p -> p.getTypeName().equals(FUEL_ANALOG)).findFirst();

            return analogSensor.map(sensor -> (int) sensor.getPeripheralSensorId());
        }

        return Optional.empty();
    }

    private void handleDigitalFuelSensorData(Position position,
                                             int sensorId,
                                             double fuelLevelChangeThreshold) {

        String sensorDataString = (String) position.getAttributes().get(SENSOR_DATA);
        if (StringUtils.isBlank(sensorDataString)) {
            return;
        }

        Optional<Long> fuelLevelPoints =
                getFuelLevelPointsFromDigitalSensorData(sensorDataString);

        if (!fuelLevelPoints.isPresent()) {
            return;
        }

        handleSensorData(position,
                sensorId,
                fuelLevelPoints.get(),
                fuelLevelChangeThreshold);
    }

    private void handleAnalogFuelSensorData(Position position,
                                            int sensorId, double fuelLevelChangeThreshold) {

        // Handle sudden drops in voltage.
        Long fuelLevel = (Long) position.getAttributes().get(ADC_1);

        handleSensorData(position,
                sensorId,
                fuelLevel,
                fuelLevelChangeThreshold);
    }

    private void handleSensorData(Position position,
                                  Integer sensorId,
                                  Long fuelLevelPoints, double fuelLevelChangeThreshold) {

        long deviceId = position.getDeviceId();
        String lookupKey = deviceId + "_" + sensorId;

        if (!previousPositions.containsKey(lookupKey)) {
            TreeMultiset<Position> positions = TreeMultiset.create(Comparator.comparing(Position::getDeviceTime));
            previousPositions.put(lookupKey, positions);
        }

        TreeMultiset<Position> positionsForDeviceSensor = previousPositions.get(lookupKey);

        if (position.getAttributes().containsKey(Position.KEY_FUEL_LEVEL)) {
            // This is a position from the DB, add to the list and move on
            positionsForDeviceSensor.add(position);
            removeFirstPositionIfNecessary(positionsForDeviceSensor);
            return;
        }

        Optional<Double> maybeCalibratedFuelLevel =
                getCalibratedFuelLevel(deviceId, sensorId, fuelLevelPoints);

        if (!maybeCalibratedFuelLevel.isPresent()) {
            // We don't have calibration data for sensor.
            return;
        }

        double calibratedFuelLevel = maybeCalibratedFuelLevel.get();
        position.set(Position.KEY_FUEL_LEVEL, calibratedFuelLevel);

        List<Position> relevantPositionsListForAverages =
                getRelevantPositionsSubList(positionsForDeviceSensor,
                                            position,
                                            minValuesForMovingAvg);

        double currentFuelLevelAverage = getAverageValue(position, relevantPositionsListForAverages);
        position.set(Position.KEY_FUEL_LEVEL, currentFuelLevelAverage);
        positionsForDeviceSensor.add(position);

        List<Position> relevantPositionsListForAlerts =
                getRelevantPositionsSubList(positionsForDeviceSensor,
                                            position,
                                            maxValuesForAlerts);

        if (!this.loadingOldDataFromDB && relevantPositionsListForAlerts.size() >= maxValuesForAlerts) {
            FuelActivity fuelActivity =
                    checkForActivity(relevantPositionsListForAlerts,
                                     deviceFuelEventMetadata,
                                     sensorId,
                                     fuelLevelChangeThreshold,
                                     fuelErrorThreshold);

            if (fuelActivity.getActivityType() != FuelActivity.FuelActivityType.NONE) {
                Log.info("FUEL ACTIVITY DETECTED: " + fuelActivity.getActivityType()
                         + " starting at: " + fuelActivity.getActivityStartTime()
                         + " ending at: " + fuelActivity.getActivityEndTime()
                         + " volume: " + fuelActivity.getChangeVolume()
                         + " start lat, long " + fuelActivity.getActivitystartPosition().getLatitude()
                         + ", " + fuelActivity.getActivitystartPosition().getLongitude()
                         + " end lat, long " + fuelActivity.getActivityEndPosition().getLatitude()
                         + ", " + fuelActivity.getActivityEndPosition().getLongitude());

                Context.getFcmPushNotificationManager().updateFuelActivity(fuelActivity);
            }
        }

        removeFirstPositionIfNecessary(positionsForDeviceSensor);
    }

    private void removeFirstPositionIfNecessary(TreeMultiset<Position> positionsForDeviceSensor) {
        if (positionsForDeviceSensor.size() > maxMessagesToLoad) {
            Position toRemove = positionsForDeviceSensor.firstEntry().getElement();
            positionsForDeviceSensor.remove(toRemove);
        }
    }

    private Date getAdjustedDate(Date fromDate, int type, int amount) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(fromDate);
        cal.add(type, amount);
        return cal.getTime();
    }

    private Double getAverageValue(Position currentPosition,
                                   List<Position> fuelLevelReadings) {

        Double total = (Double) currentPosition.getAttributes().get(Position.KEY_FUEL_LEVEL);
        for (Position position : fuelLevelReadings) {
            total += (Double) position.getAttributes().get(Position.KEY_FUEL_LEVEL);
        }

        return total / (fuelLevelReadings.size() + 1.0);
    }


    private List<Position> getRelevantPositionsSubList(TreeMultiset<Position> positionsForSensor,
                                                                       Position position,
                                                                       int minListSize) {

        if ((int) position.getAttributes().get("event") >= 100) {

            Position fromPosition = new Position();
            fromPosition.setDeviceTime(getAdjustedDate(position.getDeviceTime(),
                                                       Calendar.SECOND,
                                                       -storedEventLookAroundSeconds));

            Position toPosition = new Position();
            toPosition.setDeviceTime(getAdjustedDate(position.getDeviceTime(),
                                                     Calendar.SECOND,
                                                     storedEventLookAroundSeconds));

            List<Position> listToReturn = positionsForSensor.subMultiset(fromPosition,
                                                                         BoundType.OPEN,
                                                                         toPosition,
                                                                         BoundType.CLOSED)
                                                            .stream()
                                                            .collect(Collectors.toList());

            Log.info("STORED DATA relevant list size: " + listToReturn.size());
            return listToReturn;
        }

        if (positionsForSensor.size() <= minListSize) {
            return positionsForSensor.stream()
                                     .collect(Collectors.toList());
        }

        Position fromPosition = new Position();
        fromPosition.setDeviceTime(getAdjustedDate(position.getDeviceTime(),
                                                   Calendar.SECOND,
                                                   -currentEventLookBackSeconds));

        SortedMultiset<Position> positionsSubset =
                positionsForSensor.subMultiset(fromPosition, BoundType.OPEN, position, BoundType.CLOSED);

        if (positionsSubset.size() <= minListSize) {
            return positionsForSensor.stream()
                                     .collect(Collectors.toList());
        }

        int listMaxIndex = positionsSubset.size() - 1;

        return positionsSubset.stream()
                              .collect(Collectors.toList())
                              .subList(listMaxIndex - minListSize, listMaxIndex);
    }

    private Optional<Double> getCalibratedFuelLevel(Long deviceId, Integer sensorId, Long sensorFuelLevelPoints) {

        Optional<FuelSensorCalibration> fuelSensorCalibration =
                Context.getPeripheralSensorManager().
                        getDeviceSensorCalibrationData(deviceId, sensorId);

        if (!fuelSensorCalibration.isPresent()) {
            return Optional.empty();
        }

        // Make a B-tree map of the points to fuel level map
        TreeMap<Long, org.traccar.transforms.model.SensorPointsMap> sensorPointsToVolumeMap =
                new TreeMap<>(fuelSensorCalibration.get().getSensorPointsMap());

        SensorPointsMap previousFuelLevelInfo = sensorPointsToVolumeMap.floorEntry(sensorFuelLevelPoints).getValue();

        double currentAveragePointsPerLitre = previousFuelLevelInfo.getPointsPerLitre();
        long previousPoint = sensorPointsToVolumeMap.floorKey(sensorFuelLevelPoints);
        long previousFuelLevel = previousFuelLevelInfo.getFuelLevel();

        return Optional.of(
                ((sensorFuelLevelPoints - previousPoint) / currentAveragePointsPerLitre) + previousFuelLevel);
    }

    public FuelActivity checkForActivity(List<Position> readingsForDevice,
                                                Map<String, FuelEventMetadata> deviceFuelEventMetadata,
                                                long sensorId,
                                                double fuelLevelChangeThreshold,
                                                double fuelErrorThreshold) {

        FuelActivity fuelActivity = new FuelActivity();

        int midPoint = (readingsForDevice.size() - 1) / 2;
        double leftSum = 0, rightSum = 0;

        for (int i = 0; i <= midPoint; i++) {
            leftSum += (double) readingsForDevice.get(i).getAttributes().get(Position.KEY_FUEL_LEVEL);
            rightSum += (double) readingsForDevice.get(i + midPoint).getAttributes().get(Position.KEY_FUEL_LEVEL);
        }

        double leftMean = leftSum / (midPoint + 1);
        double rightMean = rightSum / (midPoint + 1);
        double diffInMeans = Math.abs(leftMean - rightMean);

        long deviceId = readingsForDevice.get(0).getDeviceId();
        String lookupKey = deviceId + "_" + sensorId;
        if (diffInMeans > fuelLevelChangeThreshold) {
            if (!deviceFuelEventMetadata.containsKey(lookupKey)) {

                deviceFuelEventMetadata.put(lookupKey, new FuelEventMetadata());

                FuelEventMetadata fuelEventMetadata = deviceFuelEventMetadata.get(lookupKey);
                fuelEventMetadata.setStartLevel((double) readingsForDevice.get(midPoint).getAttributes()
                                                                          .get(Position.KEY_FUEL_LEVEL));

                fuelEventMetadata.setErrorCheckStart((double) readingsForDevice.get(0)
                                                                               .getAttributes()
                                                                               .get(Position.KEY_FUEL_LEVEL));

                fuelEventMetadata.setStartTime(readingsForDevice.get(midPoint).getDeviceTime());
                fuelEventMetadata.setActivityStartPosition(readingsForDevice.get(midPoint));
            }
        }

        if (diffInMeans < fuelLevelChangeThreshold && deviceFuelEventMetadata.containsKey(lookupKey)) {
            FuelEventMetadata fuelEventMetadata = deviceFuelEventMetadata.get(lookupKey);
            fuelEventMetadata.setEndLevel((double) readingsForDevice.get(midPoint).getAttributes()
                                                                    .get(Position.KEY_FUEL_LEVEL));
            fuelEventMetadata.setErrorCheckEnd((double) readingsForDevice.get(readingsForDevice.size() - 1)
                                                                         .getAttributes()
                                                                         .get(Position.KEY_FUEL_LEVEL));
            fuelEventMetadata.setEndTime(readingsForDevice.get(midPoint).getDeviceTime());
            fuelEventMetadata.setActivityEndPosition(readingsForDevice.get(midPoint));

            double fuelChangeVolume = fuelEventMetadata.getEndLevel() - fuelEventMetadata.getStartLevel();
            double errorCheckFuelChange = fuelEventMetadata.getErrorCheckEnd() - fuelEventMetadata.getErrorCheckStart();
            double errorCheck = fuelChangeVolume * fuelErrorThreshold;
            if (fuelChangeVolume < 0.0 && errorCheckFuelChange < errorCheck) {
                fuelActivity.setActivityType(FuelActivityType.FUEL_DRAIN);
                fuelActivity.setChangeVolume(fuelChangeVolume);
                fuelActivity.setActivityStartTime(fuelEventMetadata.getStartTime());
                fuelActivity.setActivityEndTime(fuelEventMetadata.getEndTime());
                fuelActivity.setActivitystartPosition(fuelEventMetadata.getActivityStartPosition());
                fuelActivity.setActivityEndPosition(fuelEventMetadata.getActivityEndPosition());
                deviceFuelEventMetadata.remove(lookupKey);
            }

            if (fuelChangeVolume > 0.0 && errorCheckFuelChange > errorCheck) {
                fuelActivity.setActivityType(FuelActivityType.FUEL_FILL);
                fuelActivity.setChangeVolume(fuelChangeVolume);
                fuelActivity.setActivityStartTime(fuelEventMetadata.getStartTime());
                fuelActivity.setActivityEndTime(fuelEventMetadata.getEndTime());
                fuelActivity.setActivitystartPosition(fuelEventMetadata.getActivityStartPosition());
                fuelActivity.setActivityEndPosition(fuelEventMetadata.getActivityEndPosition());
                deviceFuelEventMetadata.remove(lookupKey);
            }
        }

        return fuelActivity;
    }

    private Optional<Long> getFuelLevelPointsFromDigitalSensorData(String sensorDataString) {
        if (StringUtils.isBlank(sensorDataString)) {
            return Optional.empty();
        }

        String[] sensorDataParts = sensorDataString.split(" "); // Split on space to get the 3 parts
        String frequencyString = sensorDataParts[0];
        String temperatureString = sensorDataParts[1];
        String fuelLevelPointsString = sensorDataParts[2];

        if (StringUtils.isBlank(frequencyString)
            || StringUtils.isBlank(temperatureString)
            || StringUtils.isBlank(fuelLevelPointsString)) {

            return Optional.empty();
        }

        String[] frequencyParts = frequencyString.split(FREQUENCY_PREFIX);
        if (frequencyParts.length < 2) {
            return Optional.empty();
        }

        // If frequency is > xFFF (4096), it is invalid per the spec; so ignore it.
        Long frequency = Long.parseLong(frequencyParts[1], 16);
        if (frequency > INVALID_FUEL_FREQUENCY) {
            return Optional.empty();
        }

        String[] fuelParts = fuelLevelPointsString.split(FUEL_PART_PREFIX);
        if (fuelParts.length < 2) {
            return Optional.empty();
        }

        Long fuelSensorPoints = Long.parseLong(fuelParts[1].split("\\.")[0], 16);
        return Optional.of(fuelSensorPoints);
    }
}
