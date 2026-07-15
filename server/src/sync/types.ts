export interface SyncEntities {
  people: string[];
  places: string[];
  dates: string[];
}

export interface ItemDto {
  id: string;
  type: string;
  sourceUrl: string | null;
  normalizedUrl: string | null;
  title: string;
  bodyText: string | null;
  summary: string | null;
  thumbnailUrl: string | null;
  category: string | null;
  entities: SyncEntities | null;
  eventDate: string | null; // YYYY-MM-DD
  status: string;
  isStarred: boolean;
  summaryLocked: boolean;
  tagsLocked: boolean;
  updatedAt: number;
  deletedAt: number | null;
}

export interface PulledItemDto extends ItemDto {
  seq: number;
}

export interface TagDto {
  id: string;
  label: string;
  origin: string;
  updatedAt: number;
  deletedAt: number | null;
}

export interface PulledTagDto extends TagDto {
  seq: number;
}

export interface ItemTagDto {
  itemId: string;
  tagId: string;
  updatedAt: number;
  deletedAt: number | null;
}

export interface PulledItemTagDto extends ItemTagDto {
  seq: number;
}

export interface EngagementEventDto {
  id: string;
  itemId: string;
  eventType: string;
  value: number | null;
  createdAt: number;
}

export interface PulledEngagementEventDto extends EngagementEventDto {
  seq: number;
}

export interface PullResponse {
  items: PulledItemDto[];
  tags: PulledTagDto[];
  itemTags: PulledItemTagDto[];
  engagementEvents: PulledEngagementEventDto[];
  nextCursor: number;
}

export interface PushRequest {
  items: ItemDto[];
  tags: TagDto[];
  itemTags: ItemTagDto[];
  engagementEvents: EngagementEventDto[];
}

export interface Ack {
  /** The id the client pushed. */
  clientId: string;
  /** The canonical server id for this row — normally equal to clientId, except for a tag whose
   *  (user, label) collided with an existing tag created independently on another device, in
   *  which case this is the existing tag's id and the client must re-key its local row. */
  id: string;
  seq: number;
}

export interface ItemTagAck {
  itemId: string;
  tagId: string;
  seq: number;
}

export interface PushResponse {
  itemAcks: Ack[];
  tagAcks: Ack[];
  itemTagAcks: ItemTagAck[];
  engagementEventAcks: Ack[];
  nextCursor: number;
}
