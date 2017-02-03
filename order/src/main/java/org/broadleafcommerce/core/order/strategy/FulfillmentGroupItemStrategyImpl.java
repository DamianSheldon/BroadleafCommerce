/*
 * #%L
 * BroadleafCommerce Framework
 * %%
 * Copyright (C) 2009 - 2016 Broadleaf Commerce
 * %%
 * Licensed under the Broadleaf Fair Use License Agreement, Version 1.0
 * (the "Fair Use License" located  at http://license.broadleafcommerce.org/fair_use_license-1.0.txt)
 * unless the restrictions on use therein are violated and require payment to Broadleaf in which case
 * the Broadleaf End User License Agreement (EULA), Version 1.1
 * (the "Commercial License" located at http://license.broadleafcommerce.org/commercial_license-1.1.txt)
 * shall apply.
 * 
 * Alternatively, the Commercial License may be replaced with a mutually agreed upon license (the "Custom License")
 * between you and Broadleaf Commerce. You may not use this file except in compliance with the applicable license.
 * #L%
 */
package org.broadleafcommerce.core.order.strategy;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.broadleafcommerce.core.order.dao.FulfillmentGroupItemDao;
import org.broadleafcommerce.core.order.domain.FulfillmentGroup;
import org.broadleafcommerce.core.order.domain.FulfillmentGroupItem;
import org.broadleafcommerce.core.order.domain.Order;
import org.broadleafcommerce.core.order.domain.OrderItem;
import org.broadleafcommerce.core.order.service.FulfillmentGroupService;
import org.broadleafcommerce.core.order.service.OrderItemService;
import org.broadleafcommerce.core.order.service.OrderService;
import org.broadleafcommerce.core.order.service.call.FulfillmentGroupItemRequest;
import org.broadleafcommerce.core.order.service.type.FulfillmentType;
import org.broadleafcommerce.core.order.service.workflow.CartOperationRequest;
import org.broadleafcommerce.core.pricing.service.exception.PricingException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Map.Entry;

import javax.annotation.Resource;

/**
 * @author Andre Azzolini (apazzolini)
 */
@Service("blFulfillmentGroupItemStrategy")
public class FulfillmentGroupItemStrategyImpl implements FulfillmentGroupItemStrategy {

    private static final Log LOG = LogFactory.getLog(FulfillmentGroupItemStrategyImpl.class);
    
    @Resource(name = "blFulfillmentGroupService")
    protected FulfillmentGroupService fulfillmentGroupService;
    
    @Resource(name = "blOrderItemService")
    protected OrderItemService orderItemService;
    
    @Resource(name = "blOrderService")
    protected OrderService orderService;
    
    @Resource(name = "blFulfillmentGroupItemDao")
    protected FulfillmentGroupItemDao fgItemDao;

    protected boolean removeEmptyFulfillmentGroups = true;

    @Override
    public CartOperationRequest onItemAdded(CartOperationRequest request) throws PricingException {
        Order order = request.getOrder();
        OrderItem orderItem = request.getOrderItem();
        Map<FulfillmentType, FulfillmentGroup> fulfillmentGroups = new HashMap<FulfillmentType, FulfillmentGroup>();
        FulfillmentGroup nullFulfillmentTypeGroup = null;
        
        //First, let's organize fulfillment groups according to their fulfillment type
        //We'll use the first of each type that we find. Implementors can choose to move groups / items around later.
        if (order.getFulfillmentGroups() != null) {
            for (FulfillmentGroup group : order.getFulfillmentGroups()) {
                if (group.getType() == null) {
                    if (nullFulfillmentTypeGroup == null) {
                        nullFulfillmentTypeGroup = group;
                    }
                } else {
                    if (fulfillmentGroups.get(group.getType()) == null) {
                        fulfillmentGroups.put(group.getType(), group);
                    }
                }
            }
        }
        
        FulfillmentType type = resolveFulfillmentType(orderItem);
        if (type != null) {
            FulfillmentGroup fulfillmentGroup = null;
            if (FulfillmentType.PHYSICAL_PICKUP_OR_SHIP.equals(type)) {
                //This is really a special case. "PICKUP_OR_SHIP" is convenient to allow a sku to be picked up or shipped.
                //However, it is ambiguous when actually trying to create a fulfillment group. So we default to "PHYSICAL_SHIP".
                type = FulfillmentType.PHYSICAL_SHIP;
                
                //Use the fulfillment group with the specified type
            }
            fulfillmentGroup = fulfillmentGroups.get(type);
            
            //If the null type or specified type, above were null, then we need to create a new fulfillment group
            if (fulfillmentGroup == null) {
                fulfillmentGroup = fulfillmentGroupService.createEmptyFulfillmentGroup();
                //Set the type
                fulfillmentGroup.setType(type);
                fulfillmentGroup.setOrder(order);
                order.getFulfillmentGroups().add(fulfillmentGroup);
            }
            
            fulfillmentGroup = addItemToFulfillmentGroup(order, orderItem, fulfillmentGroup);
            order = fulfillmentGroup.getOrder();
        } else {
            FulfillmentGroup fulfillmentGroup = nullFulfillmentTypeGroup;
            if (fulfillmentGroup == null) {
                fulfillmentGroup = fulfillmentGroupService.createEmptyFulfillmentGroup();
                fulfillmentGroup.setOrder(order);
                order.getFulfillmentGroups().add(fulfillmentGroup);
            }
            
            fulfillmentGroup = addItemToFulfillmentGroup(order, orderItem, fulfillmentGroup);
        }
        
        return request;
    }
    
    /**
     * Resolves the fulfillment type based on the order item. The OOB implementation uses the {@link DiscreteOrderItem#getSku()}
     * to then invoke {@link #resolveFulfillmentType(Sku)}.
     * 
     * @param discreteOrderItem
     * @return
     */
    protected FulfillmentType resolveFulfillmentType(OrderItem orderItem) {
        if (orderItem.getOrderItemDetail() == null) {
            return null;
        }
        return orderItem.getOrderItemDetail().getFulfillmentType();
    }
    
    protected FulfillmentGroup addItemToFulfillmentGroup(Order order, OrderItem orderItem, FulfillmentGroup fulfillmentGroup) throws PricingException {
        return this.addItemToFulfillmentGroup(order, orderItem, orderItem.getQuantity(), fulfillmentGroup);
    }

    protected FulfillmentGroup addItemToFulfillmentGroup(Order order, OrderItem orderItem, int quantity, FulfillmentGroup fulfillmentGroup) throws PricingException {
        FulfillmentGroupItemRequest fulfillmentGroupItemRequest = new FulfillmentGroupItemRequest();
        fulfillmentGroupItemRequest.setOrder(order);
        fulfillmentGroupItemRequest.setOrderItem(orderItem);
        fulfillmentGroupItemRequest.setQuantity(quantity);
        fulfillmentGroupItemRequest.setFulfillmentGroup(fulfillmentGroup);
        return fulfillmentGroupService.addItemToFulfillmentGroup(fulfillmentGroupItemRequest, false, false);
    }
    
    @Override
    public CartOperationRequest onItemUpdated(CartOperationRequest request) throws PricingException {
        Order order = request.getOrder();
        OrderItem orderItem = request.getOrderItem();
        Integer orderItemQuantityDelta = request.getOrderItemQuantityDelta();
        
        if (orderItemQuantityDelta == 0) {
            // If the quantity didn't change, nothing needs to happen
            return request;
        } else {
            request.setFgisToDelete(updateItemQuantity(order, orderItem, orderItemQuantityDelta));
        }
        
        return request;
    }
        
    protected List<FulfillmentGroupItem> updateItemQuantity(Order order, OrderItem orderItem, 
            Integer orderItemQuantityDelta) throws PricingException {
        List<FulfillmentGroupItem> fgisToDelete = new ArrayList<FulfillmentGroupItem>();
        boolean done = false;
        
        if (orderItemQuantityDelta > 0) {
            // If the quantity is now greater, we can simply add quantity to the first
            // fulfillment group we find that has that order item in it. 
            for (FulfillmentGroup fg : order.getFulfillmentGroups()) {
                for (FulfillmentGroupItem fgItem : fg.getFulfillmentGroupItems()) {
                    if (!done && fgItem.getOrderItem().equals(orderItem)) {
                        fgItem.setQuantity(fgItem.getQuantity() + orderItemQuantityDelta);
                        done = true;
                    }
                }
            }
        } else {
            // The quantity has been decremented. We must ensure that the appropriate number
            // of fulfillment group items are decremented as well.
            int remainingToDecrement = -1 * orderItemQuantityDelta;
            
            for (FulfillmentGroup fg : order.getFulfillmentGroups()) {
                for (FulfillmentGroupItem fgItem : fg.getFulfillmentGroupItems()) {
                    if (fgItem.getOrderItem().equals(orderItem)) {
                        if (!done &&fgItem.getQuantity() == remainingToDecrement) {
                            // Quantity matches exactly. Simply remove the item.
                            fgisToDelete.add(fgItem);
                            done = true;
                        } else if (!done && fgItem.getQuantity() > remainingToDecrement) {
                            // We have enough quantity in this fg item to facilitate the entire requsted update
                            fgItem.setQuantity(fgItem.getQuantity() - remainingToDecrement);
                            done = true;
                        } else if (!done) {
                            // We do not have enough quantity. We'll remove this item and continue searching
                            // for the remainder.
                            remainingToDecrement = remainingToDecrement - fgItem.getQuantity();
                            fgisToDelete.add(fgItem);
                        }
                    }
                }
            }
        }
        
        if (!done) {
            throw new IllegalStateException("Could not find matching fulfillment group item for the given order item");
        }
        
        return fgisToDelete;
    }

    @Override
    public CartOperationRequest onItemRemoved(CartOperationRequest request) {
        Order order = request.getOrder();
        OrderItem orderItem = request.getOrderItem();
        request.getFgisToDelete().addAll(fulfillmentGroupService.getFulfillmentGroupItemsForOrderItem(order, orderItem));
        return request;
    }
    
    @Override
    public CartOperationRequest verify(CartOperationRequest request) throws PricingException {
        Order order = request.getOrder();
        
        if (isRemoveEmptyFulfillmentGroups() && order.getFulfillmentGroups() != null) {
            ListIterator<FulfillmentGroup> fgIter = order.getFulfillmentGroups().listIterator();
            while (fgIter.hasNext()) {
                FulfillmentGroup fg = fgIter.next();
                if (fg.getFulfillmentGroupItems() == null || fg.getFulfillmentGroupItems().size() == 0) {
                    fgIter.remove();
                    fulfillmentGroupService.delete(fg);
                }
            }
        }
        
        Map<Long, Integer> oiQuantityMap = new HashMap<Long, Integer>();
        List<OrderItem> expandedOrderItems = new ArrayList<OrderItem>();

        for (OrderItem oi : order.getOrderItems()) {
            expandedOrderItems.add(oi);
        }
        
        for (OrderItem oi : expandedOrderItems) {
            Integer oiQuantity = oiQuantityMap.get(oi.getId());
            if (oiQuantity == null) {
                oiQuantity = 0;
            }
            
            oiQuantity += oi.getQuantity();
            oiQuantityMap.put(oi.getId(), oiQuantity);
        }

        for (FulfillmentGroup fg : order.getFulfillmentGroups()) {
            for (FulfillmentGroupItem fgi : fg.getFulfillmentGroupItems()) {
                Long oiId = fgi.getOrderItem().getId();
                Integer oiQuantity = oiQuantityMap.get(oiId);
                
                if (oiQuantity == null) {
                    throw new IllegalStateException("Fulfillment group items and discrete order items are not in sync. DiscreteOrderItem id: " + oiId);
                }
                oiQuantity -= fgi.getQuantity();
                oiQuantityMap.put(oiId, oiQuantity);
            }
        }
        
        for (Entry<Long, Integer> entry : oiQuantityMap.entrySet()) {
            if (!entry.getValue().equals(0)) {
                throw new IllegalStateException("Not enough fulfillment group items found for DiscreteOrderItem id: " + entry.getKey());
            }
        }
        
        return request;
    }

    @Override
    public boolean isRemoveEmptyFulfillmentGroups() {
        return removeEmptyFulfillmentGroups;
    }

    @Override
    public void setRemoveEmptyFulfillmentGroups(boolean removeEmptyFulfillmentGroups) {
        this.removeEmptyFulfillmentGroups = removeEmptyFulfillmentGroups;
    }

}