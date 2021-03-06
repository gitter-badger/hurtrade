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
package com.computedsynergy.hurtrade.sharedcomponents.models.impl;

import com.computedsynergy.hurtrade.sharedcomponents.models.interfaces.IUserModel;
import com.computedsynergy.hurtrade.sharedcomponents.models.pojos.User;
import java.util.List;
import org.sql2o.Connection;

/**
 *
 * @author Faisal Thaheem <faisal.ajmal@gmail.com>
 */
public class UserModel extends ModelBase implements IUserModel{
    
    

    @Override
    public List<User> getAllUsers() {
        
        String query = "select * from users where usertype in ('trader','dealer')";
        
        try (Connection conn = sql2o.open()) {
            List<User> users = conn.createQuery(query)
                    .executeAndFetch(User.class);
            
            return users;
        }
    }

    @Override
    public List<User> getAllUsersForOffice(int id) {
        
        String query = "select * from users where usertype in ('trader','dealer') AND id IN (select user_id from offices_users where office_id ="+ id + ")";
        
        try (Connection conn = sql2o.open()) {
            List<User> users = conn.createQuery(query)
                    .executeAndFetch(User.class);
            
            return users;
        }
        
    }

    @Override
    public User authenticate(String username, String password) {
        
        
        String query = String.format(("select * from users where username = '%s' AND password='%s' LIMIT 1"), username, password);
        User ret = null;
        
        try (Connection conn = sql2o.open()) {
            List<User> users = conn.createQuery(query)
                    .executeAndFetch(User.class);
            
            if(users.size() > 0){
                ret = users.get(0);
            }
        }
        
        return ret;
        
    }

    /**
     *
     * @param username
     * @return class User if found, null if not found
     */
    @Override
    public User getByUsername(String username) {
        
        
        String query = String.format("select * from users where username = '%s' LIMIT 1", username);
        User ret = null;
        
        try (Connection conn = sql2o.open()) {
            List<User> users = conn.createQuery(query)
                    .executeAndFetch(User.class);
            
            if(users.size() > 0){
                ret = users.get(0);
            }
        }
        
        return ret;
        
    }

}
