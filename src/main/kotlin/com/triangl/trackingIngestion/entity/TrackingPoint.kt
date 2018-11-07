package com.triangl.trackingIngestion.entity

import com.googlecode.objectify.annotation.Entity
import com.googlecode.objectify.annotation.Id
import com.googlecode.objectify.annotation.Index
import java.time.Instant
import java.util.*

@Entity
class TrackingPoint (
    @Id
    var id: String? = null,

    @Index
    var routerDataPoints: List<RouterDataPoint>,

    @Index
    var deviceId: String? = null,

    @Index
    var location: Coordinate? = null,

    @Index
    var timestamp: String? = null,

    @Index
    var lastUpdatedAt: String? = null,

    @Index
    var createdAt: String? = null
) {
    init {
        this.id ?: UUID.randomUUID().toString()
        this.lastUpdatedAt ?: Instant.now().toString()
        this.createdAt ?: Instant.now().toString()
    }

    fun parseCustomerRoutersIntoHashmap(customer: Customer): HashMap<String, Router> {
        val hashMap = HashMap<String, Router>()

        for (map in customer.maps!!) {
            for (router in map.router!!) {
                hashMap[router.id!!] = router
            }
        }

        return hashMap
    }

    fun fillMissingRouterCoordinates(routerHashMap: HashMap<String, Router>) {
        for (routerDataPoint in routerDataPoints) {
            routerDataPoint.router = routerHashMap[routerDataPoint.router!!.id]
        }
    }

    fun setLocationFromRouterDataPoint(strongestRouterDataPoint: RouterDataPoint) {
        location = Coordinate(
            x = strongestRouterDataPoint.router!!.location!!.x,
            y = strongestRouterDataPoint.router!!.location!!.y
        )
    }
}