package com.triangl.trackingIngestion.service

import com.googlecode.objectify.ObjectifyService
import com.triangl.trackingIngestion.entity.*
import com.triangl.trackingIngestion.webservices.datastore.DatastoreWs
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap

var buffer = ConcurrentHashMap<String, ArrayList<DatapointGroup>>()

@Service("computingService")
class ComputingService (
    var ingestionService: IngestionService,
    var datastoreWs: DatastoreWs
) {
    fun insertToBuffer(inputDataPoint: InputDataPoint) {
        if (buffer.containsKey(inputDataPoint.deviceId)) {

            val datapointGroup = buffer[inputDataPoint.deviceId]!!.find { inputDataPoint.timestamp >= it.startInstant
                                                                                      && inputDataPoint.timestamp <= it.endInstant }

            if (datapointGroup != null) {
                datapointGroup.dataPoints.add(inputDataPoint)
            } else {

                val newDatapointGroup = DatapointGroup(
                    inputDataPoint.timestamp,
                    inputDataPoint.deviceId
                ).apply {
                    dataPoints.add(inputDataPoint)
                }

                buffer[inputDataPoint.deviceId]!!.add(newDatapointGroup)
            }

        } else {

            val newDatapointGroup = DatapointGroup(
                inputDataPoint.timestamp,
                inputDataPoint.deviceId
            ).apply {
                dataPoints.add(inputDataPoint)
            }

            buffer[inputDataPoint.deviceId] = arrayListOf(newDatapointGroup)
        }
    }

    fun readFromBuffer():MutableMap<String, ArrayList<DatapointGroup>> = buffer

    fun startBufferWatcher() {
        val delayTime = 5000
        launch {
            while (true) {
                findElementsToCompute()

                delay(delayTime)
            }
        }
    }

    protected fun findElementsToCompute() {
        val now = LocalDateTime.now()
        for ((key, value) in buffer) {
            val valuesToRemove = arrayListOf<DatapointGroup>()

            for (item in value) {
                if (item.timeoutInstant < now && item.dataPoints.size >= 3) {
                    ObjectifyService.run { computeFromRSSI(item) }
                    valuesToRemove.add(item)
                } else if (item.timeoutInstant < now) {
                    valuesToRemove.add(item)
                }
            }

            value.removeAll(valuesToRemove)

            if (value.size == 0) {
                buffer.remove(key)
            }
        }
    }

    protected fun computeFromRSSI (datapointGroup: DatapointGroup) {
        var strongestRSSI = RouterDataPoint().apply { signalStrength = 0 }
        val routerDataPointList = ArrayList<RouterDataPoint>()
        val routersToLookUp = ArrayList<String>()

        for (dataPoint in datapointGroup.dataPoints) {
            val newRouterDataPoint = RouterDataPoint(
                router = Router(dataPoint.routerId),
                signalStrength = dataPoint.signalStrength,
                timestamp = dataPoint.timestamp.toInstant(ZoneOffset.UTC).toString()
            )

            if (newRouterDataPoint.signalStrength!! > strongestRSSI.signalStrength!!) {
                strongestRSSI = newRouterDataPoint
            }

            routerDataPointList.add(newRouterDataPoint)
            routersToLookUp.add(dataPoint.routerId)
        }

        val customerObj = datastoreWs.getRoutersById(routersToLookUp)
        val routerHashMap = parseCustomerRoutersIntoHashmap(customerObj)

        addRouterToRouterDataPoints(routerDataPointList, routerHashMap)
        strongestRSSI.router = routerHashMap[strongestRSSI.router!!.id]

        val coordinate = Coordinate(
            x = strongestRSSI.router!!.location!!.x,
            y = strongestRSSI.router!!.location!!.y
        )

        val newTrackingPoint = TrackingPoint(
            deviceId = datapointGroup.deviceId,
            location = coordinate,
            routerDataPoints = routerDataPointList,
            timestamp = strongestRSSI.timestamp
        )

        ingestionService.insertTrackingPoint(newTrackingPoint, customerObj.maps!![0].id!!)
    }

    protected fun parseCustomerRoutersIntoHashmap(customer: Customer): HashMap<String, Router> {
        val hashMap = HashMap<String, Router>()

        for (map in customer.maps!!) {
            for (router in map.router!!) {
                hashMap[router.id!!] = router
            }
        }

        return hashMap
    }

    protected fun addRouterToRouterDataPoints(routerDataPointList: List<RouterDataPoint>, routerHashMap: HashMap<String, Router>) {
        for (routerDataPoint in routerDataPointList) {
            routerDataPoint.router = routerHashMap[routerDataPoint.router!!.id]
        }
    }
}