/** 백엔드 PageResponse.from(page)와 1:1. Page<T>를 직접 받지 않는다. */
export interface PaginationResponse<T> {
  content: T[];
  number: number;
  size: number;
  totalPages: number;
  totalElements: number;
}
