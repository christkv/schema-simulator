package com.mtools.schemasimulator.schemas.shoppingcartreservation

import com.mongodb.client.MongoCollection
import com.mongodb.client.model.UpdateOptions
import org.bson.Document
import java.util.*

//abstract class ShoppingCart(val db: MongoDatabase): Scenario {
//
//    fun setup() {}
//
//    fun teardown() {}
//}

interface Action {
    fun execute(values: Map<String, Any>) : Map<String, Any>
}

class CheckoutCart(private val carts: MongoCollection<Document>,
                   private val inventories: MongoCollection<Document>,
                   private val orders: MongoCollection<Document>) : Action {
    override fun execute(values: Map<String, Any>): Map<String, Any> {
        if (!values.containsKey("userId")
            || !values.containsKey("name")
            || !values.containsKey("address")
            || !values.containsKey("payment")) {
            throw SchemaSimulatorException("a userId, name, payment and product must be passed into CheckoutCart")
        }

        val cart = carts.find(Document(mapOf(
            "_id" to values["userId"],
            "state" to "active"
        ))).first()

        cart ?: throw SchemaSimulatorException("could not locate the cart for the user ${values["userId"]}")

        // Insert an order document
        orders.insertOne(Document(mapOf(
            "createdOn" to Date(),
            "shipping" to mapOf(
                "name" to values["name"],
                "address" to values["address"]
            ),
            "payment" to values["payment"],
            "products" to cart["products"]
        )))

        // Set the cart to complete
        carts.updateOne(Document(mapOf(
            "_id" to cart["_id"]
        )), Document(mapOf(
            "\$set" to mapOf(
                "status" to "complete"
            )
        )))

        // Pull the product reservation from all inventories
        inventories.updateMany(Document(mapOf(
            "reservations._id" to values["userId"]
        )), Document(mapOf(
            "\$pull" to mapOf(
                "reservations" to mapOf(
                    "_id" to values["userId"]
                )
            )
        )))

        return mapOf()
    }
}

class ExpireCarts(private val carts: MongoCollection<Document>,
                  private val inventories: MongoCollection<Document>) : Action {
    override fun execute(values: Map<String, Any>): Map<String, Any> {
        if (!values.containsKey("cutOffDate")) {
            throw SchemaSimulatorException("a cutOffDate must be passed into ExpireCarts")
        }

        carts.find(Document(mapOf(
            "modifiedOn" to mapOf(
                "\$lte" to values["cutOffDate"],
                "state" to "active"
            )
        ))).forEach { cart ->
            val products = cart["products"] as Array<Document>
            products.forEach { product ->

                // Return the quantity to the inventory from the cart
                inventories.updateOne(Document(mapOf(
                    "_id" to product["_id"],
                    "reservations._id" to cart["_id"],
                    "reservations.quantity" to product["quantity"]
                )), Document(mapOf(
                    "\$inc" to mapOf(
                        "quantity" to product["quantity"]
                    ),
                    "\$pull" to mapOf(
                        "reservations" to mapOf(
                            "_id" to cart["_id"]
                        )
                    )
                )))

                // Set the cart to expires
                carts.updateOne(Document(mapOf(
                    "_id" to cart["_id"]
                )), Document(mapOf(
                    "\$set" to mapOf(
                        "status" to "expired"
                    )
                )))
            }
        }

        return mapOf()
    }
}

class UpdateReservationQuantityForAProduct(private val carts: MongoCollection<Document>,
                                           private val inventories: MongoCollection<Document>) : Action {
    override fun execute(values: Map<String, Any>): Map<String, Any> {
        if (!values.containsKey("userId")
            || !values.containsKey("quantity")
            || !values.containsKey("product")) {
            throw SchemaSimulatorException("a userId, product and quantity must be passed into AddProductToShoppingCart")
        }

        // Update product quantity in cart
        val result1 = UpdateProductQuantityInCartDocument(carts).execute(values)

        // Merge values
        val map = mutableMapOf<String, Any>()
        map.putAll(values)
        map.putAll(result1)

        try {
            // Attempt to update inventory
            UpdateInventoryQuantityInCartDocument(inventories).execute(map)
        } catch (exception: SchemaSimulatorException) {
            // Attempt to rollback
            RolbackCartQuantity(carts).execute(map)
        }

        return mapOf()
    }

    companion object {
        class UpdateProductQuantityInCartDocument(private val carts: MongoCollection<Document>) : Action {
            override fun execute(values: Map<String, Any>): Map<String, Any> {
                if (!values.containsKey("userId")
                    || !values.containsKey("quantity")
                    || !values.containsKey("product")) {
                    throw SchemaSimulatorException("a userId, product and quantity must be passed into AddProductToShoppingCart")
                }

                // Unwrap needed values
                var oldQuantity = 0
                val newQuantity = values["quantity"] as Int
                val document = values["product"] as Document

                // Get the cart
                val cart = carts.find(Document(mapOf(
                    "_id" to values["userId"],
                    "products._id" to document["_id"],
                    "status" to "active"
                ))).first()

                // Throw id no cart found
                cart
                    ?: throw SchemaSimulatorException("cart for user ${values["userId"]} not found in UpdateProductQuantityInCartDocument")

                // Locate the product and get the old quantity
                val products = cart.get("products") as Array<Document>
                products.forEach {
                    if (it.get("_id") == document["_id"]) {
                        oldQuantity = it.get("quantity") as Int
                    }
                }

                // Calculate the delta
                val delta = newQuantity - oldQuantity
                // Update the cart with new size
                val result = carts.updateOne(Document(mapOf(
                    "_id" to values["userId"],
                    "products._id" to document["_id"],
                    "status" to "active"
                )), Document(mapOf(
                    "\$set" to mapOf(
                        "modifiedOn" to Date(),
                        "products.$.quantity" to newQuantity
                    )
                )))

                // Failt to modify the cart
                if (result.modifiedCount == 0L) {
                    throw SchemaSimulatorException("failed to modify cart for user ${values["userId"]}")
                }

                // Return the delta
                return mapOf(
                    "delta" to delta,
                    "newQuantity" to newQuantity,
                    "oldQuantity" to oldQuantity)
            }
        }

        class UpdateInventoryQuantityInCartDocument(private val inventories: MongoCollection<Document>) : Action {
            override fun execute(values: Map<String, Any>): Map<String, Any> {
                if (!values.containsKey("userId")
                    || !values.containsKey("newQuantity")
                    || !values.containsKey("delta")
                    || !values.containsKey("product")) {
                    throw SchemaSimulatorException("a userId, product and quantity must be passed into AddProductToShoppingCart")
                }

                // Unwrap needed values
                val document = values["product"] as Document
                val delta = values["delta"] as Int
                val newQuantity = values["newQuantity"] as Int

                // Update the cart with new size
                val result = inventories.updateOne(Document(mapOf(
                    "_id" to document["_id"],
                    "reservations._id" to values["userId"],
                    "quantity" to mapOf(
                        "\$gte" to delta
                    )
                )), Document(mapOf(
                    "\$inc" to mapOf(
                        "quantity" to delta.unaryMinus()
                    ),
                    "\$set" to mapOf(
                        "reservations.$.quantity" to newQuantity,
                        "modifiedOn" to Date()
                    )
                )))

                if (result.modifiedCount == 0L) {
                    throw SchemaSimulatorException("Failed to reserve product ${document["_id"]} quantity $newQuantity for user ${values["userId"]}")
                }

                return mapOf()
            }
        }

        class RolbackCartQuantity(private val carts: MongoCollection<Document>) : Action {
            override fun execute(values: Map<String, Any>): Map<String, Any> {
                if (!values.containsKey("userId")
                    || !values.containsKey("oldQuantity")
                    || !values.containsKey("product")) {
                    throw SchemaSimulatorException("a userId, product and quantity must be passed into AddProductToShoppingCart")
                }

                // Unwrap needed values
                val document = values["product"] as Document
                val oldQuantity = values["oldQuantity"] as Int

                // Update the cart with new size
                val result = carts.updateOne(Document(mapOf(
                    "_id" to values["userId"],
                    "products._id" to document["_id"],
                    "status" to "active"
                )), Document(mapOf(
                    "\$set" to mapOf(
                        "products.\$.quantity" to oldQuantity,
                        "modifiedOn" to Date()
                    )
                )))

                if (result.modifiedCount == 0L) {
                    throw SchemaSimulatorException("Failed to rollback product ${document["_id"]} quantity $oldQuantity for user ${values["userId"]}")
                }

                return mapOf()
            }
        }
    }
}

class AddProductToShoppingCart(private val carts: MongoCollection<Document>,
                               private val inventories: MongoCollection<Document>) : Action {

    override fun execute(values: Map<String, Any>): Map<String, Any> {
        if (!values.containsKey("userId")
            || !values.containsKey("quantity")
            || !values.containsKey("product")) {
            throw SchemaSimulatorException("a userId, product and quantity must be passed into AddProductToShoppingCart")
        }

        // Attempt to Add product to shopping cart, nothing to do if it fails
        AddProductToShoppingCartDocument(carts).execute(values)
        // Attempt to update the inventory
        try {
            ReserveProductToInventoryDocument(inventories).execute(values)
        } catch (exception: SchemaSimulatorException) {
            // We have to roll back the shopping cart
            RollBackShoppingCartDocument(carts).execute(values)
        }

        return mapOf()
    }

    companion object {
        class RollBackShoppingCartDocument(
            private val carts: MongoCollection<Document>) : Action {

            override fun execute(values: Map<String, Any>): Map<String, Any> {
                if (!values.containsKey("userId") || !values.containsKey("product")) {
                    throw SchemaSimulatorException("a userId and product must be passed into RollBackShoppingCartDocument")
                }

                // Unwrap needed values
                val document = values["product"] as Document

                // Execute cart update
                val result = carts.updateOne(Document(mapOf(
                    "_id" to values["userId"]
                )), Document(mapOf(
                    "\$set" to mapOf("modifiedOn" to Date()),
                    "\$pull" to mapOf(
                        "products" to mapOf(
                            "_id" to document["productId"]
                        )
                    ))))

                if (result.modifiedCount == 1L) {
                    return mapOf()
                }

                throw SchemaSimulatorException("Failed to rollback shopping cart")
            }
        }

        class AddProductToShoppingCartDocument(
            private val carts: MongoCollection<Document>) : Action {

            override fun execute(values: Map<String, Any>): Map<String, Any> {
                if (!values.containsKey("userId") || !values.containsKey("product")) {
                    throw SchemaSimulatorException("a userId and product must be passed in into AddProductToShoppingCartDocument")
                }

                // Unwrap needed values
                val document = values["product"] as Document

                // Execute cart update
                val result = carts.updateOne(Document(mapOf(
                    "_id" to values["userId"],
                    "state" to "active"
                )), Document(mapOf(
                    "\$set" to mapOf("modifiedOn" to Date()),
                    "\$push" to mapOf(
                        "products" to mapOf(
                            "_id" to document["_id"],
                            "quantity" to values["quantity"],
                            "name" to document["name"],
                            "price" to document["price"]
                        )
                    )
                )), UpdateOptions().upsert(true))

                if (result.upsertedId != null || result.modifiedCount == 1L) {
                    return mapOf()
                }

                throw SchemaSimulatorException("Failed to upsert to update the shopping cart")
            }
        }

        class ReserveProductToInventoryDocument(
            private val inventories: MongoCollection<Document>) : Action {

            override fun execute(values: Map<String, Any>): Map<String, Any> {
                if (!values.containsKey("userId")
                    || !values.containsKey("quantity")
                    || !values.containsKey("product")) {
                    throw SchemaSimulatorException("a userId, product and quantity must be passed into ReserveProductToInventoryDocument")
                }

                // Unwrap needed values
                val document = values["product"] as Document
                val quantity = values["quantity"] as Int

                // Execute the inventory update
                val result = inventories.updateOne(Document(mapOf(
                    "_id" to document["_id"],
                    "quantity" to mapOf("\$gte" to quantity)
                )), Document(mapOf(
                    "\$inc" to mapOf("quantity" to quantity.unaryMinus()),
                    "\$push" to mapOf(
                        "reservations" to mapOf(
                            "_id" to values["userId"],
                            "quantity" to quantity,
                            "createdOn" to Date()
                        )
                    ))), UpdateOptions().upsert(false))

                if (result.modifiedCount == 1L) {
                    return mapOf()
                }

                throw SchemaSimulatorException("Failed to update the product inventory")
            }
        }
    }
}

class SchemaSimulatorException(message: String): Exception(message)
