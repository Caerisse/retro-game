package com.github.retro_game.retro_game.service.impl;

import com.github.retro_game.retro_game.dto.*;
import com.github.retro_game.retro_game.entity.*;
import com.github.retro_game.retro_game.model.Item;
import com.github.retro_game.retro_game.model.ItemRequirementsUtils;
import com.github.retro_game.retro_game.model.ItemTimeUtils;
import com.github.retro_game.retro_game.model.unit.UnitItem;
import com.github.retro_game.retro_game.repository.BodyRepository;
import com.github.retro_game.retro_game.repository.EventRepository;
import com.github.retro_game.retro_game.repository.ShipyardQueueEntryRepository;
import com.github.retro_game.retro_game.service.exception.*;
import io.vavr.Tuple;
import io.vavr.Tuple2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
class ShipyardServiceImpl implements ShipyardServiceInternal {
  private static final Logger logger = LoggerFactory.getLogger(ShipyardServiceImpl.class);
  private final ItemTimeUtils itemTimeUtils;
  private final BodyRepository bodyRepository;
  private final EventRepository eventRepository;
  private final ShipyardQueueEntryRepository shipyardQueueEntryRepository;
  private BodyServiceInternal bodyServiceInternal;
  private EventScheduler eventScheduler;

  public ShipyardServiceImpl(ItemTimeUtils itemTimeUtils, BodyRepository bodyRepository,
                             EventRepository eventRepository,
                             ShipyardQueueEntryRepository shipyardQueueEntryRepository) {
    this.itemTimeUtils = itemTimeUtils;
    this.bodyRepository = bodyRepository;
    this.eventRepository = eventRepository;
    this.shipyardQueueEntryRepository = shipyardQueueEntryRepository;
  }

  @Autowired
  public void setBodyServiceInternal(BodyServiceInternal bodyServiceInternal) {
    this.bodyServiceInternal = bodyServiceInternal;
  }

  @Autowired
  public void setEventScheduler(EventScheduler eventScheduler) {
    this.eventScheduler = eventScheduler;
  }

  @Override
  @Transactional(isolation = Isolation.REPEATABLE_READ, readOnly = true)
  public UnitsAndQueuePairDto getUnitsAndQueuePair(long bodyId, UnitTypeDto type) {
    Body body = bodyServiceInternal.getUpdated(bodyId);

    Map<UnitKind, Integer> inQueue = new EnumMap<>(UnitKind.class);

    List<ShipyardQueueEntry> shipyardQueue = body.getShipyardQueue();
    List<ShipyardQueueEntryDto> queue = new ArrayList<>(shipyardQueue.size());
    boolean first = true;
    long finishAt = 0;
    for (ShipyardQueueEntry entry : shipyardQueue) {
      UnitKind kind = entry.getKind();
      int count = entry.getCount();
      assert count >= 1;

      inQueue.put(kind, inQueue.getOrDefault(kind, 0) + count);

      var item = Item.get(kind);

      Resources cost = new Resources(item.getCost());
      cost.mul(count);

      var constructionTime = getConstructionTime(item.getCost(), body);

      long requiredTime;
      if (first) {
        Optional<Event> event = eventRepository.findFirstByKindAndParam(EventKind.SHIPYARD_QUEUE, body.getId());
        Assert.isTrue(event.isPresent(), "Event must be present");

        long readyAt = event.get().getAt().toInstant().getEpochSecond();
        long now = body.getUpdatedAt().toInstant().getEpochSecond();
        long remainingTime = readyAt - now;

        requiredTime = remainingTime + (count - 1) * constructionTime;
        finishAt = now + requiredTime;

        first = false;
      } else {
        requiredTime = count * constructionTime;
        finishAt += requiredTime;
      }

      queue.add(new ShipyardQueueEntryDto(Converter.convert(kind), count, entry.getSequence(), Converter.convert(cost),
          Date.from(Instant.ofEpochSecond(finishAt)), requiredTime));
    }

    Map<UnitKind, UnitItem> items;
    if (type == null) {
      items = UnitItem.getAll();
    } else if (type == UnitTypeDto.DEFENSE) {
      items = UnitItem.getDefense();
    } else {
      assert type == UnitTypeDto.FLEET;
      items = UnitItem.getFleet();
    }

    Resources resources = body.getResources();

    List<UnitDto> units = new ArrayList<>(items.size());
    for (Map.Entry<UnitKind, UnitItem> entry : items.entrySet()) {
      UnitKind kind = entry.getKey();
      UnitItem item = entry.getValue();

      int currentCount = body.getUnitsCount(kind);
      int futureCount = currentCount + inQueue.getOrDefault(kind, 0);

      var meetsRequirements = ItemRequirementsUtils.meetsRequirements(item, body);

      if (futureCount > 0 || meetsRequirements) {
        var time = getConstructionTime(item.getCost(), body);
        Resources cost = item.getCost();

        int maxBuildable = 0;
        if (meetsRequirements) {
          maxBuildable = Integer.MAX_VALUE;

          if (cost.getMetal() > 0.0) {
            maxBuildable = (int) (resources.getMetal() / cost.getMetal());
          }
          if (cost.getCrystal() > 0.0) {
            maxBuildable = Math.min(maxBuildable, (int) (resources.getCrystal() / cost.getCrystal()));
          }
          if (cost.getDeuterium() > 0.0) {
            maxBuildable = Math.min(maxBuildable, (int) (resources.getDeuterium() / cost.getDeuterium()));
          }

          if (kind == UnitKind.SMALL_SHIELD_DOME || kind == UnitKind.LARGE_SHIELD_DOME) {
            if (futureCount >= 1) {
              maxBuildable = 0;
            } else if (maxBuildable > 1) {
              maxBuildable = 1;
            }
          } else if (kind == UnitKind.ANTI_BALLISTIC_MISSILE || kind == UnitKind.INTERPLANETARY_MISSILE) {
            int nAnti = body.getUnitsCount(UnitKind.ANTI_BALLISTIC_MISSILE) +
                inQueue.getOrDefault(UnitKind.ANTI_BALLISTIC_MISSILE, 0);
            int nInter = body.getUnitsCount(UnitKind.INTERPLANETARY_MISSILE) +
                inQueue.getOrDefault(UnitKind.INTERPLANETARY_MISSILE, 0);
            int max = 10 * body.getBuildingLevel(BuildingKind.MISSILE_SILO) - (nAnti + 2 * nInter);
            if (kind == UnitKind.INTERPLANETARY_MISSILE) {
              max /= 2;
            }
            maxBuildable = Math.min(maxBuildable, max);
          }
        }

        units.add(new UnitDto(Converter.convert(kind), currentCount, futureCount, Converter.convert(cost), time,
            maxBuildable));
      }
    }
    // Keep the order defined in the service layer.
    units.sort(Comparator.comparing(UnitDto::getKind));

    return new UnitsAndQueuePairDto(units, queue);
  }

  @Override
  @Transactional(readOnly = true)
  public Map<UnitKind, Tuple2<Integer, Integer>> getCurrentAndFutureCounts(Body body) {
    EnumMap<UnitKind, Integer> inQueue = body.getShipyardQueue().stream()
        .collect(Collectors.toMap(
            ShipyardQueueEntry::getKind,
            ShipyardQueueEntry::getCount,
            (a, b) -> a + b,
            () -> new EnumMap<>(UnitKind.class)
        ));
    return Arrays.stream(UnitKind.values())
        .filter(kind -> body.getUnitsCount(kind) != 0 || inQueue.getOrDefault(kind, 0) != 0)
        .collect(Collectors.toMap(
            Function.identity(),
            kind -> {
              int n = body.getUnitsCount(kind);
              return Tuple.of(n, n + inQueue.getOrDefault(kind, 0));
            },
            (a, b) -> {
              throw new IllegalStateException();
            },
            () -> new EnumMap<>(UnitKind.class)
        ));
  }

  @Override
  @Transactional(isolation = Isolation.REPEATABLE_READ)
  public void build(long bodyId, UnitKindDto kind, int count) {
    UnitKind k = Converter.convert(kind);

    Body body = bodyServiceInternal.getUpdated(bodyId);

    var item = Item.get(k);
    if (!ItemRequirementsUtils.meetsRequirements(item, body)) {
      logger.warn("Constructing unit failed, requirements not met: bodyId={} kind={} count={}", bodyId, k, count);
      throw new RequirementsNotMetException();
    }

    Resources cost = item.getCost();
    cost.mul(count);
    if (!body.getResources().greaterOrEqual(cost)) {
      logger.warn("Constructing unit failed, not enough resources: bodyId={} kind={} count={}", bodyId, k, count);
      throw new NotEnoughResourcesException();
    }
    body.getResources().sub(cost);

    List<ShipyardQueueEntry> queue = body.getShipyardQueue();

    if (k == UnitKind.SMALL_SHIELD_DOME || k == UnitKind.LARGE_SHIELD_DOME) {
      // Special case for shield domes.
      if (count > 1) {
        logger.warn("Constructing unit failed, request to build more than one shield dome: bodyId={} kind={} count={}",
            bodyId, k, count);
        throw new TooManyShieldDomesException();
      }
      if (body.getUnitsCount(k) >= 1) {
        logger.warn("Constructing unit failed, the shield dome is already built: bodyId={} kind={} count={}",
            bodyId, k, count);
        throw new ShieldDomeAlreadyBuiltException();
      }
      boolean exists = queue.stream().anyMatch(e -> e.getKind() == k);
      if (exists) {
        logger.warn("Constructing unit failed, the shield dome is already in the queue: bodyId={} kind={} count={}",
            bodyId, k, count);
        throw new ShieldDomeAlreadyInQueueException();
      }
    } else if (k == UnitKind.ANTI_BALLISTIC_MISSILE || k == UnitKind.INTERPLANETARY_MISSILE) {
      // Special case for missiles, check the capacity.
      int used = body.getUnitsCount(UnitKind.ANTI_BALLISTIC_MISSILE) +
          2 * body.getUnitsCount(UnitKind.INTERPLANETARY_MISSILE);
      used += queue.stream()
          .mapToInt(e -> {
            switch (e.getKind()) {
              case ANTI_BALLISTIC_MISSILE:
                return 1;
              case INTERPLANETARY_MISSILE:
                return 2;
              default:
                return 0;
            }
          })
          .sum();
      used += (k == UnitKind.ANTI_BALLISTIC_MISSILE ? 1 : 2) * count;
      int cap = 10 * body.getBuildingLevel(BuildingKind.MISSILE_SILO);
      if (used > cap) {
        logger.warn("Constructing unit failed, not enough capacity in missile silo: bodyId={} kind={} count={}",
            bodyId, k, count);
        throw new NotEnoughCapacityException();
      }
    }

    logger.info("Constructing unit successful: bodyId={} kind={} count={}", bodyId, k, count);

    ShipyardQueueEntry last = null;
    if (!queue.isEmpty()) {
      last = queue.get(queue.size() - 1);
    }

    // Update or add an entry.
    ShipyardQueueEntry entry;
    if (last != null && last.getKind() == k) {
      // Add units to last entry.
      entry = last;
      entry.setCount(entry.getCount() + count);
    } else {
      ShipyardQueueEntryKey key = new ShipyardQueueEntryKey();
      key.setBody(body);
      key.setSequence(last == null ? 1 : last.getSequence() + 1);
      entry = new ShipyardQueueEntry();
      entry.setKey(key);
      entry.setKind(k);
      entry.setCount(count);
      shipyardQueueEntryRepository.save(entry);
    }

    if (last == null) {
      // Add event.
      long time = getConstructionTime(item.getCost(), body);
      Date now = body.getUpdatedAt();
      Date startAt = Date.from(Instant.ofEpochSecond(now.toInstant().getEpochSecond() + time));
      Event event = new Event();
      event.setAt(startAt);
      event.setKind(EventKind.SHIPYARD_QUEUE);
      event.setParam(body.getId());
      eventScheduler.schedule(event);
    }
  }

  @Override
  @Transactional(isolation = Isolation.REPEATABLE_READ)
  public void handle(Event event) {
    long bodyId = event.getParam();
    Body body = bodyRepository.getOne(bodyId);

    Date at = event.getAt();

    eventRepository.delete(event);

    List<ShipyardQueueEntry> queue = body.getShipyardQueue();

    // This shouldn't happen.
    if (queue.isEmpty()) {
      logger.error("Handling shipyard queue, queue is empty: bodyId={}", bodyId);
      return;
    }

    // Update resources, as the production may increase when we build solar satellites.
    bodyServiceInternal.updateResources(body, at);

    boolean first = true;
    for (ShipyardQueueEntry entry : queue) {
      UnitKind kind = entry.getKind();
      var item = Item.get(kind);
      long time = getConstructionTime(item.getCost(), body);

      int numBuilt;
      if (time == 0) {
        // All units can be built right now.
        numBuilt = entry.getCount();
      } else if (first) {
        // We have waited for that unit.
        numBuilt = 1;
      } else {
        // Next iteration, we haven't waited yet, we must add an event first.
        numBuilt = 0;
      }
      first = false;

      if (numBuilt > 0) {
        // Add units.
        var oldCount = body.getUnitsCount(kind);
        var newCount = oldCount + numBuilt;
        body.setUnitsCount(kind, newCount);

        int count = entry.getCount() - numBuilt;
        assert count >= 0;
        if (count == 0) {
          shipyardQueueEntryRepository.delete(entry);
          continue;
        }
        entry.setCount(count);
      }

      // Add event.
      Date startAt = Date.from(Instant.ofEpochSecond(at.toInstant().getEpochSecond() + time));
      Event newEvent = new Event();
      newEvent.setAt(startAt);
      newEvent.setKind(EventKind.SHIPYARD_QUEUE);
      newEvent.setParam(bodyId);
      eventScheduler.schedule(newEvent);
      break;
    }
  }

  @Override
  @Transactional(isolation = Isolation.REPEATABLE_READ)
  public void deleteUnitsAndQueue(Body body) {
    Optional<Event> event = eventRepository.findFirstByKindAndParam(EventKind.SHIPYARD_QUEUE, body.getId());
    event.ifPresent(eventRepository::delete);
    shipyardQueueEntryRepository.deleteAll(body.getShipyardQueue());
  }

  private long getConstructionTime(Resources cost, Body body) {
    int shipyardLevel = body.getBuildingLevel(BuildingKind.SHIPYARD);
    int naniteFactoryLevel = body.getBuildingLevel(BuildingKind.NANITE_FACTORY);
    return itemTimeUtils.getUnitConstructionTime(cost, shipyardLevel, naniteFactoryLevel);
  }
}
