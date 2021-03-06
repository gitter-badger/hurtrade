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
package com.computedsynergy.hurtrade.sharedcomponents.models.pojos;

import java.math.BigDecimal;

/**
 *
 * @author Faisal Thaheem <faisal.ajmal@gmail.com>
 */
public class CommodityUser {
    
    private String commodityname;
    private BigDecimal spread;
    private BigDecimal ratio;
    
    public CommodityUser(String c, BigDecimal s, BigDecimal r){
        this.commodityname = c;
        this.spread = s;
        this.ratio = r;
    }

    /**
     * @return the commodityname
     */
    public String getCommodityname() {
        return commodityname;
    }

    /**
     * @param commodityname the commodityname to set
     */
    public void setCommodityname(String commodityname) {
        this.commodityname = commodityname;
    }

    /**
     * @return the spread
     */
    public BigDecimal getSpread() {
        return spread;
    }

    /**
     * @param spread the spread to set
     */
    public void setSpread(BigDecimal spread) {
        this.spread = spread;
    }


    public BigDecimal getRatio() {
        return ratio;
    }

    public void setRatio(BigDecimal ratio) {
        this.ratio = ratio;
    }
}
