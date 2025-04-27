package dto

import "encoding/json"

type InterbankOtcOfferDTO struct {
	Stock          StockDescription `json:"stock"`
	SettlementDate string           `json:"settlementDate"`
	PricePerUnit   MonetaryValue    `json:"pricePerUnit"`
	Premium        MonetaryValue    `json:"premium"`
	BuyerID        ForeignBankId    `json:"buyerId"`
	SellerID       ForeignBankId    `json:"sellerId"`
	Amount         int              `json:"amount"`
	LastModifiedBy ForeignBankId    `json:"lastModifiedBy"`
}

type ForeignBankId struct {
	RoutingNumber int    `json:"routingNumber"`
	ID            string `json:"id"`
}

type StockDescription struct {
	Ticker string `json:"ticker"`
}

type PublicStock struct {
	Stock   StockDescription   `json:"stock"`
	Sellers []SellerStockEntry `json:"sellers"`
}

type SellerStockEntry struct {
	Seller ForeignBankId `json:"seller"`
	Amount int           `json:"amount"`
}

type MonetaryValue struct {
	Currency string  `json:"currency"`
	Amount   float64 `json:"amount"`
}

type PublicStocksResponse []PublicStock

type OtcNegotiation struct {
	Stock          StockDescription `json:"stock"`
	SettlementDate string           `json:"settlementDate"`
	PricePerUnit   MonetaryValue    `json:"pricePerUnit"`
	Premium        MonetaryValue    `json:"premium"`
	BuyerID        ForeignBankId    `json:"buyerId"`
	SellerID       ForeignBankId    `json:"sellerId"`
	Amount         int              `json:"amount"`
	LastModifiedBy ForeignBankId    `json:"lastModifiedBy"`
	IsOngoing      bool             `json:"isOngoing"`
}

type OptionContractDTO struct {
	ID                  uint    `json:"id"`
	PortfolioID         *uint   `json:"portfolioId,omitempty"`
	Ticker              string  `json:"ticker"`
	SecurityName        *string `json:"securityName,omitempty"`
	StrikePrice         float64 `json:"strikePrice"`
	Premium             float64 `json:"premium"`
	Quantity            int     `json:"quantity"`
	SettlementDate      string  `json:"settlementDate"`
	IsExercised         bool    `json:"isExercised"`
	BuyerID             *uint   `json:"buyerId,omitempty"`
	SellerID            *uint   `json:"sellerId,omitempty"`
	RemoteRoutingNumber *int    `json:"remoteRoutingNumber,omitempty"`
	RemoteBuyerID       *string `json:"remoteBuyerId,omitempty"`
	RemoteSellerID      *string `json:"remoteSellerId,omitempty"`
}

type InterbankMessageDTO[T any] struct {
	IdempotenceKey IdempotenceKeyDTO `json:"idempotenceKey"`
	MessageType    string            `json:"messageType"`
	Message        T                 `json:"message"`
}

type PostingDTO struct {
	Account TxAccountDTO `json:"account"`
	Amount  float64      `json:"amount"`
	Asset   AssetDTO     `json:"asset"`
}

type TxAccountDTO struct {
	Type string            `json:"type"`
	Id   *ForeignBankIdDTO `json:"id,omitempty"`
	Num  *string           `json:"num,omitempty"`
}

type ForeignBankIdDTO struct {
	RoutingNumber int    `json:"routingNumber"`
	UserId        string `json:"id"`
}

type VoteDTO struct {
	Vote    string          `json:"vote"`
	Reasons []VoteReasonDTO `json:"reasons,omitempty"`
}
type VoteReasonDTO struct {
	Reason  string      `json:"reason"`
	Posting *PostingDTO `json:"posting,omitempty"`
}
type IdempotenceKeyDTO struct {
	RoutingNumber       int    `json:"routingNumber"`
	LocallyGeneratedKey string `json:"locallyGeneratedKey"`
}

type AssetDTO struct {
	Type  string          `json:"type"`
	Asset json.RawMessage `json:"asset"`
}

type MonetaryAssetDTO struct {
	Currency string `json:"currency"`
}

type StockDescriptionDTO struct {
	Ticker string `json:"ticker"`
}

type OptionDescriptionDTO struct {
	NegotiationID  ForeignBankIdDTO    `json:"negotiationId"`
	Stock          StockDescriptionDTO `json:"stock"`
	PricePerUnit   MonetaryValueDTO    `json:"pricePerUnit"`
	SettlementDate string              `json:"settlementDate"`
	Amount         int                 `json:"amount"`
	//NegotiationID  *ForeignBankIdDTO   `json:"negotiationId,omitempty"`
}

type MonetaryValueDTO struct {
	Currency string  `json:"currency"`
	Amount   float64 `json:"amount"`
}

type InterbankTransactionDTO struct {
	Postings      []PostingDTO     `json:"postings"`
	Message       string           `json:"message"`
	TransactionId ForeignBankIdDTO `json:"transactionId"`
}

type CommitTransactionDTO struct {
	TransactionId ForeignBankIdDTO `json:"transactionId"`
}
