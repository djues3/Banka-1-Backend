package types

import "time"

type Actuary struct {
	ID           uint    `gorm:"primaryKey" json:"id,omitempty"`
	UserID       uint    `gorm:"uniqueIndex;not null" json:"userId"`
	Department   string  `gorm:"type:text;not null" json:"department,omitempty"`
	FullName     string  `gorm:"not null" json:"fullName"`
	Email        string  `gorm:"not null" json:"email"`
	LimitAmount  float64 `gorm:"default:null" json:"limit"`         // Samo za agente
	UsedLimit    float64 `gorm:"default:0" json:"usedLimit"`        // Samo za agente, resetuje se dnevno
	NeedApproval bool    `gorm:"default:false" json:"needApproval"` // Da li orderi agenta trebaju supervizorsko odobrenje
	Position     string  `gorm:"type:text" json:"position,omitempty"`
}

type Security struct {
	ID             uint     `gorm:"primaryKey" json:"id,omitempty"`
	UserID         uint     `gorm:"not null" json:"userId,omitempty"`
	Ticker         string   `gorm:"unique;not null" json:"ticker,omitempty"`
	Name           string   `gorm:"not null" json:"name,omitempty"`
	Type           string   `gorm:"type:text;not null" json:"type,omitempty"`
	Exchange       string   `gorm:"not null" json:"exchange,omitempty"`
	LastPrice      float64  `gorm:"not null" json:"lastPrice,omitempty"`
	AskPrice       float64  `gorm:"default:null" json:"ask,omitempty"`
	BidPrice       float64  `gorm:"default:null" json:"bid,omitempty"`
	Volume         int64    `gorm:"default:0" json:"availableQuantity,omitempty"`
	SettlementDate *string  `gorm:"default:null" json:"settlementDate,omitempty"` // Samo za futures i opcije
	ContractSize   int64    `gorm:"not null" json:"contractSize"`
	StrikePrice    *float64 `gorm:"default:null" json:"strikePrice,omitempty"`
	OptionType     *string  `gorm:"default:null" json:"optionType,omitempty"`
	PreviousClose  float64  `gorm:"default:null" json:"previousClose,omitempty"`
	// data for futures
}

type Order struct {
	ID                uint     `gorm:"primaryKey"`
	UserID            uint     `gorm:"not null"`
	AccountID         uint     `gorm:"not null"`
	SecurityID        uint     `gorm:"not null"`
	OrderType         string   `gorm:"type:text;not null"`
	Quantity          int      `gorm:"not null"`
	ContractSize      int      `gorm:"default:1"`
	StopPricePerUnit  *float64 `gorm:"default:null"`
	LimitPricePerUnit *float64 `gorm:"default:null"`
	Direction         string   `gorm:"type:text;not null"`
	Status            string   `gorm:"type:text;default:'pending'"`
	ApprovedBy        *uint    `gorm:"default:null"` // Supervizor koji je odobrio order
	IsDone            bool     `gorm:"default:false"`
	LastModified      int64    `gorm:"autoUpdateTime"`
	RemainingParts    *int     `gorm:"default:null"`
	AfterHours        bool     `gorm:"default:false"`
	AON               bool     `gorm:"default:false"`
	Margin            bool     `gorm:"default:false"`
	User              uint     `gorm:"foreignKey:UserID"`
	Account           uint     `gorm:"foreignKey:AccountID"`
	Security          Security `gorm:"foreignKey:SecurityID"`
	ApprovedByUser    *uint    `gorm:"foreignKey:ApprovedBy"`
}

//	type OTCTrade struct {
//		ID           uint      `gorm:"primaryKey"`
//		PortfolioID  uint      `gorm:"not null"`
//		SecurityId   uint      `gorm:"not null"`
//		SellerID     uint      `gorm:"not null"`
//		BuyerID      *uint     `gorm:"default:null"`
//		Quantity     int       `gorm:"not null"`
//		PricePerUnit float64   `gorm:"not null"`
//		Premium      float64   `gorm:"not null"`
//		SettlementAt time.Time `gorm:"not null"`
//		Status       string    `gorm:"type:text;default:'pending'"`
//		LastModified int64     `gorm:"autoUpdateTime"`
//		//promeniti da pise ime korisnika koji je menjao a ne id(komunbikacija sa user servisom)
//		ModifiedBy *uint `gorm:"default:null"`
//		CreatedAt  int64 `gorm:"autoCreateTime"`
//		//Seller       *uint      `gorm:"foreignKey:SellerID"`
//		//Buyer        *uint     `gorm:"foreignKey:BuyerID"`
//		Portfolio Portfolio `gorm:"foreignKey:PortfolioID" json:"portfolio"`
//	}
type OTCTrade struct {
	ID                  uint       `gorm:"primaryKey" json:"id"`
	PortfolioID         *uint      `gorm:"" json:"portfolioId,omitempty"`
	SecurityID          *uint      `gorm:"" json:"securityId,omitempty"`
	LocalSellerID       *uint      `gorm:"" json:"localSellerId,omitempty"`
	LocalBuyerID        *uint      `gorm:"" json:"localBuyerId,omitempty"`
	RemoteRoutingNumber *int       `gorm:"" json:"remoteRoutingNumber,omitempty"`
	RemoteNegotiationID *string    `gorm:"" json:"remoteNegotiationId,omitempty"`
	RemoteSellerID      *string    `gorm:"" json:"remoteSellerId,omitempty"`
	RemoteBuyerID       *string    `gorm:"" json:"remoteBuyerId,omitempty"`
	Ticker              string     `gorm:"not null" json:"ticker"`
	Quantity            int        `gorm:"not null" json:"quantity"`
	PricePerUnit        float64    `gorm:"not null" json:"pricePerUnit"`
	Premium             float64    `gorm:"not null" json:"premium"`
	SettlementAt        time.Time  `gorm:"not null" json:"settlementAt"`
	Status              string     `gorm:"type:text;default:'pending'" json:"status"`
	LastModified        int64      `gorm:"autoUpdateTime" json:"lastModified"`
	ModifiedBy          string     `gorm:"not null" json:"modifiedBy"`
	CreatedAt           int64      `gorm:"autoCreateTime" json:"createdAt"`
	Portfolio           *Portfolio `gorm:"foreignKey:PortfolioID" json:"portfolio"`
}
type Portfolio struct {
	ID            uint     `gorm:"primaryKey" json:"id,omitempty"`
	UserID        uint     `gorm:"not null" json:"user_id,omitempty"`
	SecurityID    uint     `gorm:"not null" json:"security_id,omitempty"`
	Quantity      int      `gorm:"not null" json:"quantity,omitempty"`
	PurchasePrice float64  `gorm:"not null" json:"purchase_price,omitempty"`
	PublicCount   int      `gorm:"default:0" json:"public"`
	CreatedAt     int64    `gorm:"autoCreateTime" json:"created_at,omitempty"`
	User          uint     `gorm:"foreignKey:UserID" json:"user,omitempty"`
	Security      Security `gorm:"foreignKey:SecurityID" json:"security"`
}

type OptionContract struct {
	ID                  uint       `gorm:"primaryKey" json:"id"`
	OTCTradeID          uint       `gorm:"not null" json:"otcTradeId"`
	RemoteContractID    *string    `gorm:"type:varchar(255);index" json:"remoteContractId,omitempty"`
	BuyerID             *uint      `gorm:"default:null" json:"buyerId,omitempty"`
	SellerID            *uint      `gorm:"default:null" json:"sellerId,omitempty"`
	RemoteBuyerID       *string    `gorm:"type:varchar(255)" json:"remoteBuyerId,omitempty"`
	RemoteSellerID      *string    `gorm:"type:varchar(255)" json:"remoteSellerId,omitempty"`
	Ticker              string     `gorm:"not null" json:"ticker"`
	PortfolioID         *uint      `gorm:"default:null" json:"portfolioId,omitempty"`
	SecurityID          *uint      `gorm:"default:null" json:"securityId,omitempty"`
	Quantity            int        `gorm:"not null" json:"quantity"`
	StrikePrice         float64    `gorm:"not null" json:"strikePrice"`
	Premium             float64    `gorm:"not null" json:"premium"`
	UID                 string     `gorm:"type:varchar(255);index" json:"uid,omitempty"`
	SettlementAt        time.Time  `gorm:"not null" json:"settlementAt"`
	TransactionID       *string    `gorm:"" json:"transactionId,omitempty"`
	IsPremiumPaid       *bool      `gorm:"default:false" json:"isPremiumPaid,omitempty"`
	Status              string     `gorm:"type:text;default:'active'" json:"status"`
	IsExercised         bool       `gorm:"default:false" json:"isExercised"`
	CreatedAt           int64      `gorm:"autoCreateTime" json:"createdAt"`
	ExercisedAt         *int64     `gorm:"default:null" json:"exercisedAt,omitempty"`
	RemoteNegotiationID *string    `gorm:"" json:"remoteNegotiationId,omitempty"`
	OTCTrade            OTCTrade   `gorm:"foreignKey:OTCTradeID" json:"otcTrade"`
	Portfolio           *Portfolio `gorm:"foreignKey:PortfolioID" json:"portfolio,omitempty"`
}

type Tax struct {
	ID            uint    `gorm:"primaryKey"`
	UserID        uint    `gorm:"foreignKey;index:idx_tax_user_createdat"`
	MonthYear     string  `gorm:"not null;index"`
	TaxableProfit float64 `gorm:"not null"`
	TaxAmount     float64 `gorm:"not null"`
	IsPaid        bool    `gorm:"default:false"`
	CreatedAt     string  `gorm:"autoCreateTime;index:idx_tax_user_createdat"`
}

type Exchange struct {
	ID        uint   `gorm:"primaryKey" json:"id,omitempty"`
	Name      string `gorm:"not null" json:"name,omitempty"`
	Acronym   string `gorm:"not null" json:"acronym,omitempty"`
	MicCode   string `gorm:"unique;not null" json:"mic_code,omitempty"`
	Country   string `gorm:"not null" json:"country,omitempty"`
	Currency  string `gorm:"not null" json:"currency,omitempty"`
	Timezone  string `gorm:"not null" json:"timezone,omitempty"`
	OpenTime  string `gorm:"not null" json:"open_time,omitempty"`
	CloseTime string `gorm:"not null" json:"close_time,omitempty"`
}

type InterbankNegotiation struct {
	ID                     uint      `gorm:"primaryKey"`
	Ticker                 string    `gorm:"not null"`
	Amount                 int       `gorm:"not null"`
	SettlementAt           time.Time `gorm:"not null"`
	PricePerUnit           float64   `gorm:"not null"`
	Premium                float64   `gorm:"not null"`
	InitiatorRoutingNumber int       `gorm:"not null"`
	InitiatorID            string    `gorm:"not null"`
	LastModifiedByID       string    `gorm:"not null"`
	LastModifiedRouting    int       `gorm:"not null"`
	RemoteNegotiationID    string    `gorm:"not null"`
	RemoteRoutingNumber    int       `gorm:"not null"`
	Status                 string    `gorm:"default:'pending'"`
	LocalBuyerUserID       *uint     `gorm:"default:null"`
	CreatedAt              int64     `gorm:"autoCreateTime"`
	UpdatedAt              int64     `gorm:"autoUpdateTime"`
}

type InterbankTxnRecord struct {
	ID            uint     `gorm:"primaryKey"`
	RoutingNumber int      `gorm:"not null;index"`
	TransactionId string   `gorm:"not null;uniqueIndex:idx_txn"`
	UserID        uint     `gorm:""`
	SecurityID    uint     `gorm:""`
	Quantity      int      `gorm:"not null"`
	PurchasePrice *float64 `gorm:"not null"`
	NeedsCredit   bool     `gorm:"not null;default:false"`
	State         string   `gorm:"not null"`
	ContractId    *uint    `gorm:"default:null"`
}

type OTCSagaPhase int

const (
	PhaseInit OTCSagaPhase = iota
	PhaseBuyerReserved
	PhaseSellerReceived
	PhaseOwnershipRemoved
	PhaseOwnershipTransferred
	PhaseVerified
)

type OTCSagaState struct {
	UID   string       `gorm:"primaryKey;not null"`
	Phase OTCSagaPhase `gorm:"not null"`
}
