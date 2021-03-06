/*
 * Copyright 2016 Faisal Thaheem <faisal.ajmal@gmail.com>.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.computedsynergy.hurtrade.hurcpu.cpu.Tasks;

import com.computedsynergy.hurtrade.hurcpu.cpu.RequestConsumers.ClientRequestConsumer;
import com.computedsynergy.hurtrade.sharedcomponents.amqp.AmqpBase;
import com.computedsynergy.hurtrade.sharedcomponents.dataexchange.Quote;
import com.computedsynergy.hurtrade.sharedcomponents.dataexchange.QuoteList;
import com.computedsynergy.hurtrade.sharedcomponents.dataexchange.SourceQuote;
import com.computedsynergy.hurtrade.sharedcomponents.models.impl.LedgerModel;
import com.computedsynergy.hurtrade.sharedcomponents.models.impl.QuoteModel;
import com.computedsynergy.hurtrade.sharedcomponents.models.pojos.CommodityUser;
import com.computedsynergy.hurtrade.sharedcomponents.models.pojos.Position;
import com.computedsynergy.hurtrade.sharedcomponents.dataexchange.updates.ClientUpdate;
import com.computedsynergy.hurtrade.sharedcomponents.models.impl.SavedPositionModel;
import com.computedsynergy.hurtrade.sharedcomponents.models.pojos.User;
import com.computedsynergy.hurtrade.sharedcomponents.util.Constants;
import com.computedsynergy.hurtrade.sharedcomponents.util.HurUtil;
import com.computedsynergy.hurtrade.sharedcomponents.util.RedisUtil;
import static com.computedsynergy.hurtrade.sharedcomponents.util.RedisUtil.EXPIRY_LOCK_USER_POSITIONS;
import static com.computedsynergy.hurtrade.sharedcomponents.util.RedisUtil.TIMEOUT_LOCK_USER_POSITIONS;
import static com.computedsynergy.hurtrade.sharedcomponents.util.RedisUtil.getLockNameForUserPositions;
import static com.computedsynergy.hurtrade.sharedcomponents.util.RedisUtil.getUserPositionsKeyName;
import com.github.jedis.lock.JedisLock;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;
import java.io.IOException;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import redis.clients.jedis.Jedis;

/**
 *
 * @author Faisal Thaheem <faisal.ajmal@gmail.com>
 *
 * Subscribes to the rate queue and notifies the clients in case there is an
 * update after applying client specific spreads
 *
 * Also processes client's positions effectively calculating P/L
 *
 * Listens to the client's requests
 */
public class ClientAccountTask extends AmqpBase {
    
    SavedPositionModel savedPositionModel = new SavedPositionModel();

    Gson gson = new Gson();
    QuoteModel quoteModel = null;

    private User _self = null;

    //mq related
    private String _clientExchangeName = "";
    private String _myRateQueueName = "";
    private String _incomingQueueName = "";
    private String _outgoingQueueName = "";
    private AMQP.BasicProperties.Builder propsBuilder = new AMQP.BasicProperties.Builder();

    //redis related
    private String _userPositionsKeyName = "";

    //finances related variables
    private BigDecimal _floating = BigDecimal.ZERO;
    private BigDecimal _usedMarginBuy = BigDecimal.ZERO;
    private BigDecimal _usedMarginSell = BigDecimal.ZERO;


    public ClientAccountTask(User u){
        _self = u;

        _userPositionsKeyName = getUserPositionsKeyName(_self.getUseruuid());

        _clientExchangeName = HurUtil.getClientExchangeName(_self.getUseruuid());
        _incomingQueueName = HurUtil.getClientIncomingQueueName(u.getUseruuid());
        _outgoingQueueName = HurUtil.getClientOutgoingQueueName(u.getUseruuid());
        _myRateQueueName = Constants.QUEUE_NAME_RATES + _self.getUseruuid().toString();

        this.init();
    }

    public void init(){

        try {
            super.setupAMQP();

            quoteModel = new QuoteModel();

            Map<String, Object> args = new HashMap<String, Object>();
            args.put("x-max-length", 5); //retain only 5 latest messages for clients

            channel.exchangeDeclare(_clientExchangeName, "direct", true);

            channel.queueDeclare(_incomingQueueName, true, false, false, args);
            channel.queueBind(_incomingQueueName, _clientExchangeName, "request");

            channel.queueDeclare(_outgoingQueueName, true, false, false, args);
            channel.queueBind(_outgoingQueueName, _clientExchangeName, "response");

            channel.queueDeclare(_myRateQueueName, true, false, false, null);
            channel.queueBind(_myRateQueueName, Constants.EXCHANGE_NAME_RATES, _self.getUseruuid().toString());

            //consume command related messages from user
            ClientRequestConsumer consumer = new ClientRequestConsumer(_self, channel, _clientExchangeName);

            channel.basicConsume(_incomingQueueName, false, "command" + _self.getUseruuid().toString(), consumer);

            //consume the rates specific messages and send updates to the user as a result
            channel.basicConsume(_myRateQueueName, false, "rates-"+ _self.getUseruuid().toString(),
                    new DefaultConsumer(channel) {
                        @Override
                        public void handleDelivery(String consumerTag,
                                                   Envelope envelope,
                                                   AMQP.BasicProperties properties,
                                                   byte[] body)
                                throws IOException {
                            String routingKey = envelope.getRoutingKey();
                            String contentType = properties.getContentType();
                            long deliveryTag = envelope.getDeliveryTag();


                            SourceQuote quote = new Gson().fromJson(new String(body), SourceQuote.class);
                            processQuote(quote);
                            publishAccountStatus();

                            channel.basicAck(deliveryTag, false);
                        }
                    });
        }catch (Exception ex){
            Logger.getLogger(this.getClass().getName()).log(Level.SEVERE, null, ex);
        }
    }

    private void processQuote(SourceQuote quote) {
        

        
        //introduce the spread as defined for this client for the symbols in the quote list
        Map<String, CommodityUser> userCommodities = RedisUtil
                                                .getInstance()
                                                .getCachedUserCommodities(_self.getUseruuid());
        
        
        QuoteList sourceQuotes = quote.getQuoteList();
        QuoteList clientQuotes = new QuoteList();
        for(String k:sourceQuotes.keySet()){
            
            Quote sourceQuote = sourceQuotes.get(k);

            Quote q = null;
            //include propagation to client only if they are allowed this symbol
            if(null != userCommodities && userCommodities.containsKey(k)){
                q = new Quote(
                        sourceQuote.bid,
                        sourceQuote.bid.add(userCommodities.get(k).getSpread()),
                        sourceQuote.quoteTime,
                        BigDecimal.ZERO,
                        sourceQuote.name,
                        sourceQuote.lotSize
                );
                
            }else{
                q = new Quote(
                        sourceQuote.bid,
                        sourceQuote.ask,
                        sourceQuote.quoteTime,
                        BigDecimal.ZERO,
                        sourceQuote.name,
                        sourceQuote.lotSize
                );
            }
            clientQuotes.put(k, q);
        }
        
        
        Map<UUID, Position> clientPositions = null;
        try(Jedis jedis = RedisUtil.getInstance().getJedisPool().getResource()){
            JedisLock lock = new JedisLock(
                        jedis, 
                        RedisUtil.getLockNameForUserProcessing(_self.getUseruuid()),
                        RedisUtil.TIMEOUT_LOCK_USER_PROCESSING, 
                        RedisUtil.EXPIRY_LOCK_USER_PROCESSING
                    );

            
            try {
                if(lock.acquire()){

                    //process client's positions
                    clientPositions = updateClientPositions(_self, clientQuotes);
                    lock.release();

                }else{
                    Logger.getLogger(this.getClass().getName()).log(Level.SEVERE, "Could not lock user position in redis {0}",  _self.getUsername());
                }
            } catch (InterruptedException ex) {
                Logger.getLogger(this.getClass().getName()).log(Level.SEVERE, null, ex);
            }
        }
        

        //set the quotes as they are needed when trading
        String serializedQuotes = gson.toJson(clientQuotes);
        RedisUtil.getInstance().setSeriaizedQuotesForClient(serializedQuotes, _self.getUseruuid());
        
        //remove the quotes not allowed for this client
        for(String k:sourceQuotes.keySet()){
            if(!userCommodities.containsKey(k)){
                clientQuotes.remove(k);
            }
        }



        propsBuilder.type("update");
        AMQP.BasicProperties props = propsBuilder.build();

        ClientUpdate update = new ClientUpdate(clientPositions, clientQuotes);
        
        String serializedQuotesForClient = gson.toJson(update);
        //finally send out the quotes and updated positions to the client
        try{
            channel.basicPublish(
                    _clientExchangeName,
                    "response",
                    props,
                    serializedQuotesForClient.getBytes()
            );
        }catch(Exception ex){

            Logger.getLogger(this.getClass().getName()).log(Level.SEVERE, null, ex);
        }

    }
    
    private Map<UUID, Position> updateClientPositions(User user, QuoteList clientQuotes)
    {

        try(Jedis jedis = RedisUtil.getInstance().getJedisPool().getResource()){
            
            JedisLock lock = new JedisLock(
                    jedis,
                    getLockNameForUserPositions(_userPositionsKeyName),
                    TIMEOUT_LOCK_USER_POSITIONS, EXPIRY_LOCK_USER_POSITIONS
            );

            try{
                if(lock.acquire()){

                    //get client positions
                    Map<UUID, Position> positions =
                            gson.fromJson(jedis.get(_userPositionsKeyName), Constants.POSITIONS_MAP_TYPE);

                    if(positions == null){
                        positions = new HashMap<>();
                    }

                    for(Position p:positions.values()){
                        p.processQuote(clientQuotes);

                        _floating = _floating.add(p.getCurrentPl());

                        if(p.getOrderType().equals(Position.ORDER_TYPE_BUY)){
                            _usedMarginBuy = _usedMarginBuy.add(p.getUsedMargin());
                        }else{
                            _usedMarginSell = _usedMarginSell.add(p.getUsedMargin());
                        }
                    }
                    
                    //set client positions
                    String serializedPositions = gson.toJson(positions);
                    jedis.set(_userPositionsKeyName, serializedPositions);

                    //todo - on a different thread not so often.
                    //dump client's positions to db only if there are any positions
//                    if(positions.size() > 0){
//                        SavedPosition p = new SavedPosition(user.getId(), serializedPositions);
//                        savedPositionModel.savePosition(p);
//                    }

                    lock.release();

                    //insert quotes to db
                    quoteModel.saveQuote(user.getId(), clientQuotes.values());

                    return positions;

                }else{
                    Logger.getLogger(this.getClass().getName()).log(Level.SEVERE, "Could not get lock to process user positions for {0}", user.getUsername());
                }
            }catch(Exception ex){
                Logger.getLogger(this.getClass().getName()).log(Level.SEVERE, "Error processing user positions for {0}", user.getUsername());
                Logger.getLogger(this.getClass().getName()).log(Level.SEVERE, ex.getMessage(), ex);
            }
            //or return null if problems
            return null;
        }
    }

    private void publishAccountStatus()
    {
        BigDecimal availableCash = new LedgerModel().GetAvailableCashForUser(_self.getId());

        BigDecimal usedMargin = (_usedMarginBuy.subtract(_usedMarginSell)).abs();
        BigDecimal equity = availableCash.add(_floating);
        BigDecimal usable = equity.subtract(usedMargin);

        HashMap<String, BigDecimal> accountStatus = new HashMap<>();
        accountStatus.put("floating", _floating);
        accountStatus.put("usedMargin", usedMargin);
        accountStatus.put("equity", equity);
        accountStatus.put("usable", usable);
        accountStatus.put("availableCash", availableCash);

        String serializedAccountStatus = gson.toJson(accountStatus);

        propsBuilder.type("accountStatus");
        AMQP.BasicProperties props = propsBuilder.build();

        try {
            channel.basicPublish(
                    _clientExchangeName,
                    "response",
                    props,
                    serializedAccountStatus.getBytes()
            );

            _floating = BigDecimal.ZERO;
            _usedMarginSell = BigDecimal.ZERO;
            _usedMarginBuy = BigDecimal.ZERO;

        } catch (Exception ex) {
            Logger.getLogger(this.getClass().getName()).log(Level.SEVERE, ex.getMessage(), ex);
        }
    }
    
}
