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
package com.computedsynergy.hurtrade.hurcpu;

import com.beust.jcommander.JCommander;
import com.computedsynergy.hurtrade.hurcpu.bootstrap.Bootstrap;
import com.computedsynergy.hurtrade.hurcpu.cpu.AuthRequestProcessor;
import com.computedsynergy.hurtrade.hurcpu.cpu.BackOfficeRequestProcessor;
import com.computedsynergy.hurtrade.sharedcomponents.commandline.CommandLineOptions;

/**
 *
 * @author Faisal Thaheem <faisal.ajmal@gmail.com>
 */
public class HurCpu {
    public static void main(String[] args) throws Exception{
        
        new JCommander(CommandLineOptions.getInstance(), args);
        Bootstrap bs = new Bootstrap();
        bs.bootstrap();

        BackOfficeRequestProcessor boRequestProcessor = new BackOfficeRequestProcessor();
        boRequestProcessor.initialize();

        AuthRequestProcessor authProcessor = new AuthRequestProcessor();
        authProcessor.init();
        
        while(true){
            Thread.sleep(100000);
        }
    }
}
