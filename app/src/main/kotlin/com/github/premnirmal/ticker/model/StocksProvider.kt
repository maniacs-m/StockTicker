package com.github.premnirmal.ticker.model

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.SystemClock
import android.text.TextUtils
import android.util.Log
import com.github.premnirmal.ticker.RxBus
import com.github.premnirmal.ticker.CrashLogger
import com.github.premnirmal.ticker.RefreshReceiver
import com.github.premnirmal.ticker.SimpleSubscriber
import com.github.premnirmal.ticker.Tools
import com.github.premnirmal.ticker.events.NoNetworkEvent
import com.github.premnirmal.ticker.network.Stock
import com.github.premnirmal.ticker.network.StocksApi
import com.trello.rxlifecycle.kotlin.bindToLifecycle
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import org.joda.time.format.ISODateTimeFormat
import rx.Observable
import rx.Observable.Operator
import rx.Subscriber
import rx.android.schedulers.AndroidSchedulers
import rx.functions.Func1
import rx.schedulers.Schedulers
import java.text.DateFormatSymbols
import java.util.*
import javax.inject.Singleton

/**
 * Created by premnirmal on 2/28/16.
 */
@Singleton
class StocksProvider(private val api: StocksApi, private val bus: RxBus,
    private val context: Context, private val preferences: SharedPreferences) : IStocksProvider {

  companion object {

    private val LAST_FETCHED = "LAST_FETCHED"
    private val NEXT_FETCH = "NEXT_FETCH"
    private val POSITION_LIST = "POSITION_LIST"
    private val DEFAULT_STOCKS = "^SPY,GOOG,AAPL,MSFT,YHOO,TSLA,^DJI"

    private val TICKER_CLEANUP = "TICKER_CLEANUP"

    val SORTED_STOCK_LIST = "SORTED_STOCK_LIST"
    val GOOGLE_SYMBOLS = Arrays.asList(".DJI", ".IXIC")
    val _GOOGLE_SYMBOLS = Arrays.asList("^DJI", "^IXIC")
  }

  private val tickerList: MutableList<String>
  private val stockList: MutableList<Stock> = ArrayList()
  private val positionList: MutableList<Stock>
  private var lastFetched: Long = 0L
  private var nextFetch: Long = 0L
  private val storage: StocksStorage

  init {
    storage = StocksStorage(context)
    val tickerListVars = preferences.getString(SORTED_STOCK_LIST, DEFAULT_STOCKS)
    tickerList = ArrayList(Arrays.asList(
        *tickerListVars.split(",".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()))

    // cleanup
    val tickers = ArrayList(tickerList)
    if (!preferences.getBoolean(TICKER_CLEANUP, false)) {
      tickerList.remove("SNDK")
      for (ticker in tickers) {
        if (ticker.startsWith("^")) {
          tickerList.remove(ticker)
        }
      }
      preferences.edit().putBoolean(TICKER_CLEANUP, true).apply()
    }

    positionList = Tools.stringToPositions(preferences.getString(POSITION_LIST, ""))

    val tickerList = Tools.toCommaSeparatedString(this.tickerList)
    preferences.edit().putString(SORTED_STOCK_LIST, tickerList).apply()
    try {
      lastFetched = preferences.getLong(LAST_FETCHED, 0L)
    } catch (e: Exception) {
      lastFetched = 0L
    }
    nextFetch = preferences.getLong(NEXT_FETCH, 0)
    if (lastFetched == null) {
      fetch().subscribe(SimpleSubscriber<List<Stock>>())
    } else {
      fetchLocal()
    }
  }

  private fun fetchLocal() {
    synchronized(stockList, {
      stockList.clear()
      stockList.addAll(storage.readSynchronous())
      if (!stockList.isEmpty()) {
        sortStockList()
        sendBroadcast()
      } else {
        fetch().subscribe(SimpleSubscriber<List<Stock>>())
      }
    })
  }

  private fun save() {
    preferences.edit().putString(POSITION_LIST, Tools.positionsToString(positionList))
        .putString(SORTED_STOCK_LIST, Tools.toCommaSeparatedString(tickerList))
        .putLong(LAST_FETCHED, lastFetched)
        .apply()
    storage.save(stockList).subscribe(object : Subscriber<Boolean>() {
      override fun onCompleted() {

      }

      override fun onError(e: Throwable) {
        e.printStackTrace()
      }

      override fun onNext(success: Boolean) {
        if (!success) {
          CrashLogger.logException(RuntimeException("Save failed"))
        }
      }
    })
  }

  override fun fetch(): Observable<List<Stock>> {
    return api.getStocks(tickerList)
        .doOnError { e ->
          CrashLogger.logException(RuntimeException("Encountered onError when fetching stocks",
              e)) // why does this happen?
          e.printStackTrace()
          scheduleUpdate(SystemClock.elapsedRealtime() + (60 * 1000)) // 1 minute
          AlarmScheduler.sendBroadcast(context)
        }
        .doOnNext { stocks ->
          synchronized(stockList, {
            stockList.clear()
            stockList.addAll(stocks)
            lastFetched = api.lastFetched
            save()
            sendBroadcast()
          })
        }
        .subscribeOn(Schedulers.io())
        .observeOn(AndroidSchedulers.mainThread())
  }

  private fun sendBroadcast() {
    scheduleUpdate(msToNextAlarm)
    AlarmScheduler.sendBroadcast(context)
  }

  private val msToNextAlarm: Long
    get() = AlarmScheduler.msOfNextAlarm()

  private fun scheduleUpdate(msToNextAlarm: Long) {
    val updateTime = AlarmScheduler.scheduleUpdate(msToNextAlarm, context)
    nextFetch = updateTime.millis
    preferences.edit().putLong(NEXT_FETCH, nextFetch).apply()
  }

  override fun addStock(ticker: String): Collection<String> {
    if (tickerList.contains(ticker)) {
      return tickerList
    }
    tickerList.add(ticker)
    save()
    fetch().subscribe(SimpleSubscriber<List<Stock>>())
    return tickerList
  }

  override fun addPosition(ticker: String?, shares: Int, price: Float) {
    if (ticker != null) {
      synchronized(stockList, {
        var position = getStock(ticker)
        if (position == null) {
          position = Stock()
        }
        if (!ticker.contains(ticker)) {
          tickerList.add(ticker)
        }
        position.symbol = ticker
        position.PositionPrice = price
        position.PositionShares = shares
        positionList.remove(position)
        if (shares != 0) {
          position.IsPosition = true
          positionList.add(position)
          stockList.remove(position)
          stockList.add(position)
          save()
          fetch().subscribe(SimpleSubscriber<List<Stock>>())
        } else {
          removePosition(ticker)
        }
      })
    }
  }

  override fun removePosition(ticker: String?) {
    val position = getStock(ticker) ?: return
    position.IsPosition = false
    positionList.remove(position)
    save()
  }

  override fun addStocks(tickers: Collection<String>): Collection<String> {
    for (ticker in tickers) {
      if (!tickerList.contains(ticker)) {
        tickerList.add(ticker)
      }
    }
    save()
    fetch().subscribe(SimpleSubscriber<List<Stock>>())
    return tickerList
  }

  override fun removeStock(ticker: String): Collection<String> {
    synchronized(stockList, {
      val ticker2 = "^" + ticker // in case it was an index
      tickerList.remove(ticker)
      tickerList.remove(ticker2)
      val dummy = Stock()
      val dummy2 = Stock()
      dummy.symbol = ticker
      dummy2.symbol = ticker2
      stockList.remove(dummy)
      stockList.remove(dummy2)
      positionList.remove(dummy)
      positionList.remove(dummy2)
      save()
      scheduleUpdate(msToNextAlarm)
      AlarmScheduler.sendBroadcast(context)
      return tickerList
    })
  }

  override fun getStocks(): Collection<Stock> {
    synchronized(stockList, {
      sortStockList()

      val newStockList = ArrayList<Stock>()
      var added: Boolean
      // Set all positions
      for (stock in stockList) {
        added = false
        for (pos in positionList) {
          if (!added && stock.symbol == pos.symbol) {
            stock.IsPosition = true
            stock.PositionShares = pos.PositionShares
            stock.PositionPrice = pos.PositionPrice
            newStockList.add(stock)
            added = true
          }
        }
        if (!added) {
          newStockList.add(stock)
        }
      }
      return newStockList
    })
  }

  private fun sortStockList() {
    synchronized(stockList, {
      if (Tools.autoSortEnabled()) {
        Collections.sort(stockList)
      } else {
        Collections.sort(stockList) { lhs, rhs ->
          tickerList.indexOf(lhs.symbol).toInt().compareTo(tickerList.indexOf(rhs.symbol))
        }
      }
    })
  }

  override fun rearrange(tickers: List<String>): Collection<Stock> {
    tickerList.clear()
    tickerList.addAll(tickers)
    save()
    sendBroadcast()
    return getStocks()
  }

  override fun getStock(ticker: String?): Stock? {
    synchronized(stockList, {
      val dummy = Stock()
      dummy.symbol = ticker
      val index = stockList.indexOf(dummy)
      if (index >= 0) {
        val stock = stockList[index]
        return stock
      }
      return null
    })
  }

  override fun getTickers(): List<String> {
    return ArrayList(tickerList)
  }

  override fun lastFetched(): String {
    val fetched: String
    if (lastFetched > 0L) {
      val time = DateTime(lastFetched)
      fetched = createTimeString(time)
    } else {
      fetched = ""
    }
    return fetched
  }

  private fun createTimeString(time: DateTime): String {
    val fetched: String
    val dfs = DateFormatSymbols.getInstance(Locale.ENGLISH)
    val fetchedDayOfWeek = time.dayOfWeek().get()
    val today = DateTime.now().dayOfWeek().get()
    if (today == fetchedDayOfWeek) {
      fetched = time.toString(ISODateTimeFormat.hourMinute())
    } else {
      val day: String = dfs.shortWeekdays[fetchedDayOfWeek % 7 + 1]
      val timeStr: String = time.toString(ISODateTimeFormat.hourMinute())
      fetched = "$timeStr $day"
    }
    return fetched
  }

  override fun nextFetch(): String {
    val fetch: String
    if (nextFetch > 0) {
      val time = DateTime(nextFetch)
      fetch = createTimeString(time)
    } else {
      fetch = ""
    }
    return fetch
  }
}