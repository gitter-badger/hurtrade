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

import com.computedsynergy.hurtrade.sharedcomponents.amqp.AmqpBase;
import com.computedsynergy.hurtrade.sharedcomponents.commandline.CommandLineOptions;
import com.computedsynergy.hurtrade.sharedcomponents.dataexchange.SourceQuote;
import com.computedsynergy.hurtrade.sharedcomponents.dataexchange.updates.BackofficeUpdate;
import com.computedsynergy.hurtrade.sharedcomponents.dataexchange.updates.ClientUpdate;
import com.computedsynergy.hurtrade.sharedcomponents.models.impl.OfficeModel;
import com.computedsynergy.hurtrade.sharedcomponents.models.pojos.Office;
import com.computedsynergy.hurtrade.sharedcomponents.models.pojos.Position;
import com.computedsynergy.hurtrade.sharedcomponents.models.impl.UserModel;
import com.computedsynergy.hurtrade.sharedcomponents.models.pojos.User;
import com.computedsynergy.hurtrade.sharedcomponents.util.Constants;
import com.computedsynergy.hurtrade.sharedcomponents.util.RedisUtil;
import com.google.gson.Gson;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author Faisal Thaheem <faisal.ajmal@gmail.com>
 */
public class OfficePositionsDispatchTask extends AmqpBase {

    private final String officeExhcangeName;
    private final int officeId;
    private List<String> officeUsers  = new ArrayList<>();
    private Office _self = null;

    private Object lockOfficeUsers = new Object(); //governs access to officeUsers
    private Object lockChannelWrite = new Object(); //governs access to write access on mq channel

    private Timer tmrUserKeysUpdateTimer = new Timer(true);
    private Timer tmrOfficePositionsDispatchTimer = new Timer(true);


    private Gson gson = new Gson();

    public void initialize() throws IOException, TimeoutException {

        try{
            super.setupAMQP();

            //Do bindinds etc
            Setup();
        }catch (Exception ex){
            Logger.getLogger(this.getClass().getName()).log(Level.SEVERE, ex.getMessage(), ex);
        }

    }

    public OfficePositionsDispatchTask(
                                       String officeExchangeName,
                                       int officeId
    ) {

        this.officeExhcangeName = officeExchangeName;
        this.officeId = officeId;
        this.loadOffice();


        //get list of all users for the office and store them for faster access, we will later require this
        //list to create update events to send to the dealers
        updateOfficeUsers();

    }

    private void loadOffice() {

        OfficeModel model = new OfficeModel();
        _self = model.getOffice(officeId);
    }

    private void Setup(){

        try {

            //consume the rates specific messages and send updates to the user as a result
            channel.basicConsume(Constants.EXCHANGE_NAME_RATES, false, "rates-" + _self.getOfficename(),
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


                            Runnable task = () -> {
                                SourceQuote quote = new Gson().fromJson(new String(body), SourceQuote.class);
                                dispatchPositions(quote);
                            };
                            task.run();

                            channel.basicAck(deliveryTag, false);
                        }
                    });
        }catch (Exception ex){
            Logger.getLogger(this.getClass().getName()).log(Level.SEVERE, null, ex);
        }
//
//        //update list as defined
//        tmrUserKeysUpdateTimer.schedule(
//            new TimerTask() {
//                @Override
//                public void run() {
//                    updateOfficeUsers();
//                }
//            },
//            CommandLineOptions.getInstance().usersKeysUpdateTimerInterval,
//            CommandLineOptions.getInstance().usersKeysUpdateTimerInterval
//        );
//
//        //send office positions to dealers
//        tmrOfficePositionsDispatchTimer.schedule(
//            new TimerTask(){
//                @Override
//                public void run() {
//                dispatchPositions();
//                }
//            },
//            CommandLineOptions.getInstance().officePositionsUpdateTimer,
//            CommandLineOptions.getInstance().officePositionsUpdateTimer
//        );
    }

    private void updateOfficeUsers(){

        try {
            UserModel uModel = new UserModel();

            List<User> users = uModel.getAllUsersForOffice(officeId);
            synchronized (lockOfficeUsers) {

                officeUsers.clear(); //to ensure we remove users who have been closed, disabled etc

                for (User uItem : users) {
                    officeUsers.add(uItem.getUsername());
                }
            }
        }catch(Exception ex){
            Logger.getLogger(this.getClass().getName()).log(Level.SEVERE, ex.getMessage(), ex);
        }
    }

    private void dispatchPositions(SourceQuote quote){

        try {
            BackofficeUpdate update = new BackofficeUpdate();

            //get positions for all clients
            List<String> usernames = null;
            Map<String, List<Position>> userPositions = new HashMap<>();

            synchronized (lockOfficeUsers) {
                usernames = new ArrayList<>(officeUsers);
            }
            for (String username : usernames) {
                List<Position> positions = RedisUtil.getInstance().GetUserPositions(username);
                if (positions.size() > 0) {
                    userPositions.put(username, positions);
                }
            }

            AMQP.BasicProperties.Builder builder = new AMQP.BasicProperties.Builder();
            builder.type("officePositions");
            AMQP.BasicProperties props = builder.build();

            update.setQuotes(quote.getQuoteList());
            update.setUserPositions(userPositions);


            String json = gson.toJson(update);
            synchronized (lockChannelWrite) {
                try {
                    channel.basicPublish(officeExhcangeName, "todealer", props, json.getBytes());
                } catch (Exception ex) {
                    Logger.getLogger(this.getClass().getName()).log(Level.SEVERE, ex.getMessage(), ex);
                }
            }
        }catch (Exception ex){
            Logger.getLogger(this.getClass().getName()).log(Level.SEVERE, ex.getMessage(), ex);
        }
    }
}
