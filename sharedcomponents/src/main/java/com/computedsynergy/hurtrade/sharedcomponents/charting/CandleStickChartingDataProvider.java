package com.computedsynergy.hurtrade.sharedcomponents.charting;

import com.computedsynergy.hurtrade.sharedcomponents.dataexchange.charting.CandleStick;
import com.computedsynergy.hurtrade.sharedcomponents.models.impl.QuoteModel;
import org.joda.time.DateTime;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by faisal.t on 7/3/2017.
 */
public class CandleStickChartingDataProvider {

    /**
     *
     * @param resolution hourly, daily, weekly
     * @param samples number of samples > 1....n
     * @return
     */
    public List<CandleStick> GetChartData(String commodity, int user_id, String resolution, int samples){

        List<CandleStick> lst = new ArrayList<>();

        if(resolution.equalsIgnoreCase("hourly")){
            lst = GetHourly(commodity, user_id, samples);
        }

        return lst;
    }

    private List<CandleStick> GetHourly(String commodity, int user_id, int samples){

        List<CandleStick> lst = new ArrayList<>();

        QuoteModel quotes = new QuoteModel();

        DateTime end = DateTime.now(); end = end.minusSeconds(end.getSecondOfMinute());
        DateTime start = end.minusMinutes(end.getMinuteOfHour()).minusHours(1);

        do{
            CandleStick stick = quotes.GetCandleStickForPeriod(
                    commodity,
                    user_id,
                    start.toDate(),
                    end.toDate()
                );

            if(stick.getClose() == null) {
                break;
            }
            lst.add(stick);

            if(end.getMinuteOfHour() > 0){
                end = end.minusMinutes(end.getMinuteOfHour());
            }
            end = end.minusHours(1);
            start = end.minusHours(1);
        }while( --samples > 0);

        return lst;
    }
}
